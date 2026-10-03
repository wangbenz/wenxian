package com.wenxian.admin.plan;

import com.wenxian.admin.audit.AdminOperationLogRepository;
import com.wenxian.admin.model.ModelCatalogAdminRepository;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 管理三档方案的草稿聚合、发布校验和不可变历史。
 *
 * <p>发布只写入不可变方案，不直接改写兼容 {@code ai_model_task_route}。游戏端开启推演点运行时后，
 * 新请求按 ACTIVE 方案冻结档位、价格与模型路由；关闭开关或没有 ACTIVE 方案时继续走兼容路径。
 * 只有结构完整、三个主模型互异，且所有启用模型存在与当前配置版本匹配的受控验证证据时才允许发布。
 * 历史版本只能克隆成新草稿，不能重新激活或原地修改。</p>
 */
@Service
public class ServicePlanApplicationService {
    private static final Set<String> TIER_CODES = Set.of("LIGHT", "BALANCED", "DEEP");
    private static final Set<String> REQUIRED_CAPABILITIES = Set.of("CHAT", "JSON_MODE");
    private final ServicePlanAdminRepository repository;
    private final ModelCatalogAdminRepository modelCatalog;
    private final AdminOperationLogRepository operationLog;
    private final boolean gameRuntimeEnabled;

    @Autowired
    public ServicePlanApplicationService(ServicePlanAdminRepository repository,
                                         ModelCatalogAdminRepository modelCatalog,
                                         AdminOperationLogRepository operationLog,
                                         @Value("${wenxian.turn-credit.enabled:false}") boolean gameRuntimeEnabled) {
        this.repository = repository;
        this.modelCatalog = modelCatalog;
        this.operationLog = operationLog;
        this.gameRuntimeEnabled = gameRuntimeEnabled;
    }

    /** 仅供不启用游戏端运行时的仓储单元测试使用。 */
    ServicePlanApplicationService(ServicePlanAdminRepository repository,
                                  ModelCatalogAdminRepository modelCatalog,
                                  AdminOperationLogRepository operationLog) {
        this(repository, modelCatalog, operationLog, false);
    }

    public List<ServicePlanView> plans() {
        CatalogSnapshot catalog = catalogSnapshot();
        return repository.findVersions().stream().map(version -> view(version, catalog)).toList();
    }

    public ServicePlanView plan(String planId) {
        ServicePlanAdminRepository.PlanVersionRecord version = repository.findVersion(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "推演服务方案不存在"));
        return view(version, catalogSnapshot());
    }

    @Transactional
    public ServicePlanView createDraft(String basedOnVersionId, String actor) {
        ServicePlanAdminRepository.PlanVersionRecord source = null;
        if (basedOnVersionId != null && !basedOnVersionId.isBlank()) {
            source = repository.findVersion(basedOnVersionId.strip())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "克隆来源方案不存在"));
        }
        long versionNumber = repository.allocateVersionNumber();
        String planId = "service-plan-" + UUID.randomUUID();
        Instant now = Instant.now();
        String createdBy = actor(actor);
        ServicePlanAdminRepository.PlanVersionRecord draft = new ServicePlanAdminRepository.PlanVersionRecord(
                planId, versionNumber, "DRAFT", 0, source == null ? "BALANCED" : source.defaultTierCode(),
                source == null ? "" : "基于 v" + source.versionNumber() + " 创建草稿",
                source == null ? null : source.id(), createdBy, now, null, null, null);
        repository.insertVersion(draft);
        if (source == null) {
            repository.insertTiers(planId, defaultTiers());
        } else {
            repository.insertTiers(planId, repository.findTiers(source.id()));
            repository.insertTaskRoutes(planId, repository.findTaskRoutes(source.id()));
        }
        operationLog.append("CREATE_SERVICE_PLAN_DRAFT", "创建推演服务方案草稿 v" + versionNumber);
        return view(draft, catalogSnapshot());
    }

    @Transactional
    public ServicePlanView saveDraft(String planId, SaveDraftCommand command) {
        if (command == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "草稿内容不能为空");
        }
        ServicePlanAdminRepository.PlanVersionRecord current = lockedDraft(planId, command.expectedRevision());
        String defaultTier = tierCode(command.defaultTierCode());
        String changeNote = optional(command.changeNote(), 500, "变更说明不能超过 500 个字符");
        List<ServicePlanAdminRepository.TierRecord> tiers = normalizeTiers(command.tiers());
        if (!TIER_CODES.contains(defaultTier)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认档位必须是轻量、均衡或深度之一");
        }
        List<ServicePlanAdminRepository.TaskRouteRecord> routes = normalizeRoutes(command.taskRoutes());
        validateReferencesExist(tiers, routes);
        repository.replaceDraftAggregate(planId, current.revision(), defaultTier, changeNote,
                tiers, routes);
        operationLog.append("UPDATE_SERVICE_PLAN_DRAFT", "保存推演服务方案草稿 v" + current.versionNumber());
        return plan(planId);
    }

    @Transactional
    public ServicePlanView publish(String planId, long expectedRevision, String actor) {
        repository.lockPublicationSequence();
        ServicePlanAdminRepository.PlanVersionRecord current = lockedDraft(planId, expectedRevision);
        CatalogSnapshot catalog = catalogSnapshot();
        List<ServicePlanAdminRepository.TierRecord> tiers = repository.findTiers(planId);
        List<ServicePlanAdminRepository.TaskRouteRecord> routes = repository.findTaskRoutes(planId);
        List<String> blockers = publicationBlockers(current, tiers, routes, catalog);
        if (!blockers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "方案尚不能发布：" + String.join("；", blockers));
        }
        Instant now = Instant.now();
        repository.replaceModelSnapshots(planId, modelSnapshots(tiers, routes, catalog, now));
        repository.retireActive(now);
        if (repository.activateDraft(planId, expectedRevision, actor(actor), now) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "草稿已变化，请刷新后重新发布");
        }
        operationLog.append("PUBLISH_SERVICE_PLAN", "发布推演服务方案 v" + current.versionNumber()
                + (gameRuntimeEnabled ? "；后续平台请求按新方案冻结" : "；游戏端运行开关尚未开启"));
        return plan(planId);
    }

    private ServicePlanAdminRepository.PlanVersionRecord lockedDraft(String planId, long expectedRevision) {
        ServicePlanAdminRepository.PlanVersionRecord current = repository.lockVersion(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "推演服务方案不存在"));
        if (!"DRAFT".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已发布或已退休的方案不可原地修改，请克隆新草稿");
        }
        if (current.revision() != expectedRevision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "草稿已被其他操作更新，请刷新后重试");
        }
        return current;
    }

    private ServicePlanView view(ServicePlanAdminRepository.PlanVersionRecord version, CatalogSnapshot catalog) {
        List<ServicePlanAdminRepository.TierRecord> tiers = repository.findTiers(version.id());
        List<ServicePlanAdminRepository.TaskRouteRecord> routes = repository.findTaskRoutes(version.id());
        List<String> blockers = publicationBlockers(version, tiers, routes, catalog);
        return new ServicePlanView(version.id(), version.versionNumber(), version.status(), version.revision(),
                version.defaultTierCode(), version.changeNote(), version.basedOnVersionId(), version.createdBy(),
                text(version.createdAt()), version.publishedBy(), text(version.publishedAt()), text(version.retiredAt()),
                "DRAFT".equals(version.status()) && blockers.isEmpty(), blockers,
                gameRuntimeEnabled && "ACTIVE".equals(version.status()),
                tiers.stream().map(this::tierView).toList(), routes.stream().map(this::routeView).toList(),
                repository.findModelSnapshots(version.id()).stream().map(this::snapshotView).toList());
    }

    private List<ServicePlanAdminRepository.ModelSnapshotRecord> modelSnapshots(
            List<ServicePlanAdminRepository.TierRecord> tiers,
            List<ServicePlanAdminRepository.TaskRouteRecord> routes,
            CatalogSnapshot catalog,
            Instant capturedAt) {
        LinkedHashSet<ModelKey> referenced = new LinkedHashSet<>();
        tiers.forEach(tier -> {
            addModel(referenced, tier.primaryProviderCode(), tier.primaryModelCode());
            if (tier.fallbackEnabled()) addModel(referenced, tier.fallbackProviderCode(), tier.fallbackModelCode());
        });
        routes.forEach(route -> {
            addModel(referenced, route.primaryProviderCode(), route.primaryModelCode());
            if (route.fallbackEnabled()) addModel(referenced, route.fallbackProviderCode(), route.fallbackModelCode());
        });
        return referenced.stream().map(key -> {
            ModelCatalogAdminRepository.ProviderRecord provider = catalog.providers().get(key.providerCode());
            ModelCatalogAdminRepository.ModelRecord model = catalog.models().get(key);
            ModelCatalogAdminRepository.ValidationRecord validation = catalog.validations().get(key);
            if (provider == null || model == null || validation == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "发布证据在事务内发生变化，请刷新后重试");
            }
            return new ServicePlanAdminRepository.ModelSnapshotRecord(key.providerCode(), key.modelCode(),
                    provider.configVersion(), model.configVersion(), validation.id(), capturedAt);
        }).toList();
    }

    private static void addModel(Set<ModelKey> models, String provider, String model) {
        ModelKey key = modelKey(provider, model);
        if (key != null) models.add(key);
    }

    private List<String> publicationBlockers(ServicePlanAdminRepository.PlanVersionRecord version,
                                             List<ServicePlanAdminRepository.TierRecord> tiers,
                                             List<ServicePlanAdminRepository.TaskRouteRecord> routes,
                                             CatalogSnapshot catalog) {
        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        Map<String, ServicePlanAdminRepository.TierRecord> byCode = new HashMap<>();
        tiers.forEach(tier -> byCode.put(tier.tierCode(), tier));
        if (!byCode.keySet().equals(TIER_CODES) || tiers.size() != 3) {
            blockers.add("必须且只能配置轻量、均衡、深度三个档位");
        }
        if (!TIER_CODES.contains(version.defaultTierCode())) {
            blockers.add("默认档位无效");
        }
        Set<ModelKey> primaryModels = new HashSet<>();
        for (String code : List.of("LIGHT", "BALANCED", "DEEP")) {
            ServicePlanAdminRepository.TierRecord tier = byCode.get(code);
            if (tier == null) continue;
            String label = tier.displayName().isBlank() ? code : tier.displayName();
            if (!tier.enabled()) blockers.add(label + "尚未启用");
            if (tier.pointCost() <= 0) blockers.add(label + "的推演点必须大于 0");
            ModelKey primary = modelKey(tier.primaryProviderCode(), tier.primaryModelCode());
            if (primary == null) {
                blockers.add(label + "尚未选择主模型");
            } else {
                if (!primaryModels.add(primary)) blockers.add("三个档位的主模型必须互不相同");
                validatePublishModel(label + "主模型", primary, catalog, blockers);
            }
            if (tier.fallbackEnabled()) {
                ModelKey fallback = modelKey(tier.fallbackProviderCode(), tier.fallbackModelCode());
                if (fallback == null) blockers.add(label + "已启用回退但尚未选择回退模型");
                else {
                    if (fallback.equals(primary)) blockers.add(label + "的主模型和回退模型不能相同");
                    validatePublishModel(label + "回退模型", fallback, catalog, blockers);
                }
            }
        }
        Set<String> supportedTasks = new HashSet<>(repository.findSupportedTaskCodes());
        for (ServicePlanAdminRepository.TaskRouteRecord route : routes) {
            String label = route.tierCode() + "/" + route.taskCode();
            if (!supportedTasks.contains(route.taskCode())) blockers.add(label + "不是当前支持的模型任务");
            ModelKey primary = modelKey(route.primaryProviderCode(), route.primaryModelCode());
            if (primary == null) blockers.add(label + "尚未选择覆盖主模型");
            else validatePublishModel(label + "主模型", primary, catalog, blockers);
            if (route.fallbackEnabled()) {
                ModelKey fallback = modelKey(route.fallbackProviderCode(), route.fallbackModelCode());
                if (fallback == null) blockers.add(label + "已启用回退但尚未选择回退模型");
                else {
                    if (fallback.equals(primary)) blockers.add(label + "的主模型和回退模型不能相同");
                    validatePublishModel(label + "回退模型", fallback, catalog, blockers);
                }
            }
        }
        return List.copyOf(blockers);
    }

    private static void validatePublishModel(String label, ModelKey key, CatalogSnapshot catalog,
                                             Set<String> blockers) {
        ModelCatalogAdminRepository.ProviderRecord provider = catalog.providers().get(key.providerCode());
        ModelCatalogAdminRepository.ModelRecord model = catalog.models().get(key);
        if (provider == null || model == null) {
            blockers.add(label + "不存在于模型库");
            return;
        }
        if (!provider.enabled()) blockers.add(label + "所属供应商未启用");
        if (provider.platformSecretCiphertext() == null || provider.platformSecretCiphertext().isBlank()) {
            blockers.add(label + "所属供应商未配置平台密钥");
        }
        if (!model.platformEnabled()) blockers.add(label + "未允许平台调用");
        ModelCatalogAdminRepository.ValidationRecord validation = catalog.validations().get(key);
        if (validation == null) {
            blockers.add(label + "尚无受控验证记录");
            return;
        }
        if (validation.providerConfigVersion() != provider.configVersion()
                || validation.modelConfigVersion() != model.configVersion()) {
            blockers.add(label + "验证记录已因配置变化失效");
            return;
        }
        if (!"PASSED".equals(validation.status())) {
            blockers.add(label + "最近一次受控验证未通过");
            return;
        }
        Set<String> capabilities = csv(validation.capabilityCodes());
        if (!capabilities.containsAll(REQUIRED_CAPABILITIES)) {
            blockers.add(label + "验证未覆盖 CHAT 和 JSON_MODE");
        }
    }

    private List<ServicePlanAdminRepository.TierRecord> normalizeTiers(List<TierCommand> commands) {
        if (commands == null || commands.size() != 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须且只能提交轻量、均衡、深度三个档位");
        }
        Set<String> seen = new HashSet<>();
        List<ServicePlanAdminRepository.TierRecord> result = new ArrayList<>();
        for (TierCommand command : commands) {
            if (command == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "档位内容不能为空");
            String code = tierCode(command.tierCode());
            if (!TIER_CODES.contains(code) || !seen.add(code)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "档位编码必须且只能包含 LIGHT、BALANCED、DEEP");
            }
            if (command.pointCost() < 0 || command.pointCost() > 100_000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "草稿推演点必须在 0-100000 之间");
            }
            ModelPair primary = optionalPair(command.primaryProviderCode(), command.primaryModelCode(), "档位主模型");
            ModelPair fallback = optionalPair(command.fallbackProviderCode(), command.fallbackModelCode(), "档位回退模型");
            if (command.fallbackEnabled() && fallback == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "启用档位回退时必须同时选择供应商和模型");
            }
            result.add(new ServicePlanAdminRepository.TierRecord(code,
                    required(command.displayName(), 40, "档位名称需为 1-40 个字符"),
                    optional(command.description(), 240, "档位说明不能超过 240 个字符"),
                    command.pointCost(), command.enabled(), provider(primary), model(primary),
                    provider(fallback), model(fallback), command.fallbackEnabled()));
        }
        return result;
    }

    private List<ServicePlanAdminRepository.TaskRouteRecord> normalizeRoutes(List<TaskRouteCommand> commands) {
        if (commands == null) return List.of();
        Set<String> supported = new HashSet<>(repository.findSupportedTaskCodes());
        Set<String> seen = new HashSet<>();
        List<ServicePlanAdminRepository.TaskRouteRecord> result = new ArrayList<>();
        for (TaskRouteCommand command : commands) {
            if (command == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务覆盖内容不能为空");
            String tier = tierCode(command.tierCode());
            String task = code(command.taskCode(), 48, "任务编码格式不正确");
            if (!TIER_CODES.contains(tier) || !supported.contains(task)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务覆盖的档位或任务编码无效");
            }
            if (!seen.add(tier + "/" + task)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同一档位不能重复覆盖同一模型任务");
            }
            ModelPair primary = requiredPair(command.primaryProviderCode(), command.primaryModelCode(), "任务覆盖主模型");
            ModelPair fallback = optionalPair(command.fallbackProviderCode(), command.fallbackModelCode(), "任务覆盖回退模型");
            if (command.fallbackEnabled() && fallback == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "启用任务回退时必须同时选择供应商和模型");
            }
            BigDecimal temperature = command.temperature() == null ? new BigDecimal("0.700") : command.temperature();
            if (temperature.compareTo(BigDecimal.ZERO) < 0 || temperature.compareTo(new BigDecimal("2.000")) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型温度必须在 0-2 之间");
            }
            if (command.timeoutSeconds() < 1 || command.timeoutSeconds() > 300
                    || command.maximumRetries() < 0 || command.maximumRetries() > 2
                    || command.maximumOutputTokens() != null && command.maximumOutputTokens() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务超时、重试或输出上限超出允许范围");
            }
            result.add(new ServicePlanAdminRepository.TaskRouteRecord(tier, task, primary.providerCode(),
                    primary.modelCode(), provider(fallback), model(fallback), temperature, command.timeoutSeconds(),
                    command.maximumRetries(), command.maximumOutputTokens(), command.disableThinking(),
                    command.fallbackEnabled()));
        }
        return result;
    }

    private void validateReferencesExist(List<ServicePlanAdminRepository.TierRecord> tiers,
                                         List<ServicePlanAdminRepository.TaskRouteRecord> routes) {
        Set<ModelKey> available = catalogSnapshot().models().keySet();
        for (ServicePlanAdminRepository.TierRecord tier : tiers) {
            assertExists(modelKey(tier.primaryProviderCode(), tier.primaryModelCode()), available);
            assertExists(modelKey(tier.fallbackProviderCode(), tier.fallbackModelCode()), available);
        }
        for (ServicePlanAdminRepository.TaskRouteRecord route : routes) {
            assertExists(modelKey(route.primaryProviderCode(), route.primaryModelCode()), available);
            assertExists(modelKey(route.fallbackProviderCode(), route.fallbackModelCode()), available);
        }
    }

    private static void assertExists(ModelKey key, Set<ModelKey> available) {
        if (key != null && !available.contains(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "所选模型不存在于模型库：" + key.providerCode() + "/" + key.modelCode());
        }
    }

    private CatalogSnapshot catalogSnapshot() {
        Map<String, ModelCatalogAdminRepository.ProviderRecord> providers = new HashMap<>();
        modelCatalog.findProviders().forEach(provider -> providers.put(provider.providerCode(), provider));
        Map<ModelKey, ModelCatalogAdminRepository.ModelRecord> models = new HashMap<>();
        modelCatalog.findModels().forEach(model -> models.put(
                new ModelKey(model.providerCode(), model.modelCode()), model));
        Map<ModelKey, ModelCatalogAdminRepository.ValidationRecord> validations = new HashMap<>();
        modelCatalog.findLatestValidations().forEach(validation -> validations.put(
                new ModelKey(validation.providerCode(), validation.modelCode()), validation));
        return new CatalogSnapshot(Map.copyOf(providers), Map.copyOf(models), Map.copyOf(validations));
    }

    private List<ServicePlanAdminRepository.TierRecord> defaultTiers() {
        return List.of(
                new ServicePlanAdminRepository.TierRecord("LIGHT", "轻量", "响应更快，适合日常推进", 0,
                        true, null, null, null, null, false),
                new ServicePlanAdminRepository.TierRecord("BALANCED", "均衡", "质量与速度均衡", 0,
                        true, null, null, null, null, false),
                new ServicePlanAdminRepository.TierRecord("DEEP", "深度", "适合关键剧情与复杂推演", 0,
                        true, null, null, null, null, false));
    }

    private ServicePlanView.TierView tierView(ServicePlanAdminRepository.TierRecord tier) {
        return new ServicePlanView.TierView(tier.tierCode(), tier.displayName(), tier.description(),
                tier.pointCost(), tier.enabled(), tier.primaryProviderCode(), tier.primaryModelCode(),
                tier.fallbackProviderCode(), tier.fallbackModelCode(), tier.fallbackEnabled());
    }

    private ServicePlanView.TaskRouteView routeView(ServicePlanAdminRepository.TaskRouteRecord route) {
        return new ServicePlanView.TaskRouteView(route.tierCode(), route.taskCode(), route.primaryProviderCode(),
                route.primaryModelCode(), route.fallbackProviderCode(), route.fallbackModelCode(),
                route.temperature(), route.timeoutSeconds(), route.maximumRetries(), route.maximumOutputTokens(),
                route.disableThinking(), route.fallbackEnabled());
    }

    private ServicePlanView.ModelSnapshotView snapshotView(ServicePlanAdminRepository.ModelSnapshotRecord snapshot) {
        return new ServicePlanView.ModelSnapshotView(snapshot.providerCode(), snapshot.modelCode(),
                snapshot.providerConfigVersion(), snapshot.modelConfigVersion(), snapshot.validationRunId(),
                text(snapshot.capturedAt()));
    }

    private static ModelPair requiredPair(String provider, String model, String label) {
        ModelPair pair = optionalPair(provider, model, label);
        if (pair == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空");
        return pair;
    }

    private static ModelPair optionalPair(String provider, String model, String label) {
        boolean providerBlank = provider == null || provider.isBlank();
        boolean modelBlank = model == null || model.isBlank();
        if (providerBlank && modelBlank) return null;
        if (providerBlank || modelBlank) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "必须同时选择供应商和模型");
        }
        String providerCode = code(provider, 32, "供应商编码格式不正确");
        String modelCode = model.strip();
        if (modelCode.length() > 80 || !modelCode.matches("[A-Za-z0-9._-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型编码格式不正确");
        }
        return new ModelPair(providerCode, modelCode);
    }

    private static ModelKey modelKey(String provider, String model) {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) return null;
        return new ModelKey(provider, model);
    }

    private static String provider(ModelPair pair) { return pair == null ? null : pair.providerCode(); }

    private static String model(ModelPair pair) { return pair == null ? null : pair.modelCode(); }

    private static String tierCode(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static String code(String value, int maximumLength, String message) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > maximumLength
                || !normalized.matches("[A-Z0-9._-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return normalized;
    }

    private static String required(String value, int maximumLength, String message) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > maximumLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return normalized;
    }

    private static String optional(String value, int maximumLength, String message) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > maximumLength) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        return normalized;
    }

    private static String actor(String value) {
        String normalized = value == null ? "admin-console" : value.strip();
        return normalized.isBlank() ? "admin-console" : normalized.substring(0, Math.min(64, normalized.length()));
    }

    private static String text(Instant value) { return value == null ? null : value.toString(); }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> result = new HashSet<>();
        for (String item : value.split(",")) result.add(item.strip().toUpperCase(Locale.ROOT));
        return result;
    }

    public record SaveDraftCommand(long expectedRevision, String defaultTierCode, String changeNote,
                                   List<TierCommand> tiers, List<TaskRouteCommand> taskRoutes) { }

    public record TierCommand(String tierCode, String displayName, String description, int pointCost,
                              boolean enabled, String primaryProviderCode, String primaryModelCode,
                              String fallbackProviderCode, String fallbackModelCode, boolean fallbackEnabled) { }

    public record TaskRouteCommand(String tierCode, String taskCode, String primaryProviderCode,
                                   String primaryModelCode, String fallbackProviderCode, String fallbackModelCode,
                                   BigDecimal temperature, int timeoutSeconds, int maximumRetries,
                                   Integer maximumOutputTokens, boolean disableThinking, boolean fallbackEnabled) { }

    private record ModelPair(String providerCode, String modelCode) { }
    private record ModelKey(String providerCode, String modelCode) { }
    private record CatalogSnapshot(Map<String, ModelCatalogAdminRepository.ProviderRecord> providers,
                                   Map<ModelKey, ModelCatalogAdminRepository.ModelRecord> models,
                                   Map<ModelKey, ModelCatalogAdminRepository.ValidationRecord> validations) { }
}

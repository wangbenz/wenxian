package com.wenxian.admin.model;

import com.wenxian.admin.SecretCipher;
import com.wenxian.admin.audit.AdminOperationLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Arrays;

/**
 * 编排管理台模型目录读取、供应商维护和运行时任务路由切换。
 *
 * <p>本服务只管理当前模型目录，不直接参与 game-api 的模型调用。停用前会检查主路由和已启用回退路由；
 * 任何校验失败都不会写入部分配置，平台密钥也不会进入返回 DTO 或审计详情。</p>
 */
@Service
public class ModelCatalogApplicationService {
    private static final Set<String> KNOWN_CAPABILITIES = Set.of(
            "CHAT", "JSON_MODE", "JSON_SCHEMA", "TOOLS", "STREAMING",
            "THINKING_CONTROL", "CACHE_USAGE");
    private static final Set<String> ROUTING_CAPABILITIES = Set.of("CHAT", "JSON_MODE");
    private final ModelCatalogAdminRepository repository;
    private final AdminOperationLogRepository operationLog;
    private final String masterKey;

    public ModelCatalogApplicationService(ModelCatalogAdminRepository repository,
                                          AdminOperationLogRepository operationLog,
                                          @Value("${wenxian.ai-config-master-key:wenxian-local-config-key-change-me}")
                                          String masterKey) {
        this.repository = repository;
        this.operationLog = operationLog;
        this.masterKey = masterKey;
    }

    public List<ModelCatalogProviderView> providers() {
        int taskCount = repository.countPlatformTasks();
        Map<String, List<ModelCatalogAdminRepository.ModelRecord>> modelsByProvider = new LinkedHashMap<>();
        repository.findModels().forEach(model -> modelsByProvider
                .computeIfAbsent(model.providerCode(), ignored -> new ArrayList<>()).add(model));
        Map<ModelKey, TreeSet<String>> tasksByModel = new LinkedHashMap<>();
        Map<ModelKey, TreeSet<String>> primaryTasksByModel = new LinkedHashMap<>();
        repository.findEffectiveRouteReferences().forEach(reference -> {
            ModelKey key = new ModelKey(reference.providerCode(), reference.modelCode());
            tasksByModel.computeIfAbsent(key, ignored -> new TreeSet<>()).add(reference.taskCode());
            if (reference.primary()) {
                primaryTasksByModel.computeIfAbsent(key, ignored -> new TreeSet<>()).add(reference.taskCode());
            }
        });
        Map<ModelKey, ModelCatalogAdminRepository.ValidationRecord> validations = new LinkedHashMap<>();
        repository.findLatestValidations().forEach(validation -> validations.put(
                new ModelKey(validation.providerCode(), validation.modelCode()), validation));
        Map<ModelKey, List<ModelCatalogAdminRepository.ServicePlanReference>> plansByModel = new LinkedHashMap<>();
        repository.findServicePlanReferences().forEach(reference -> plansByModel
                .computeIfAbsent(new ModelKey(reference.providerCode(), reference.modelCode()),
                        ignored -> new ArrayList<>()).add(reference));

        return repository.findProviders().stream().map(provider -> providerView(provider,
                modelsByProvider.getOrDefault(provider.providerCode(), List.of()), tasksByModel,
                primaryTasksByModel, validations, plansByModel, taskCount)).toList();
    }

    @Transactional
    public void updateProvider(String providerCode, String displayName, String baseUrl,
                               boolean enabled, boolean userKeyEnabled, String apiKey) {
        String provider = normalizeProvider(providerCode);
        String name = required(displayName, 80, "供应商名称需为 1-80 个字符");
        String endpoint = baseUrl == null ? "" : baseUrl.strip();
        if (!endpoint.matches("https://[^\\s]{3,240}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接地址必须是 HTTPS 地址");
        }
        if (!enabled && providerReferenced(provider)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该供应商仍承担平台主任务或回退任务，请先切换对应路由");
        }

        boolean replaceKey = apiKey != null && !apiKey.isBlank();
        String ciphertext = replaceKey ? SecretCipher.encrypt(apiKey.strip(), masterKey) : null;
        Instant now = Instant.now();
        int changed = repository.updateProvider(provider, name, endpoint, ciphertext, replaceKey,
                enabled, userKeyEnabled, now);
        if (changed == 0) {
            repository.insertProvider(provider, name, endpoint, ciphertext, enabled, userKeyEnabled, now);
        }
        operationLog.append("UPDATE_GENERATION_PROVIDER", "更新推演供应商 " + provider);
    }

    @Transactional
    public void updateModel(String providerCode, String modelCode, String displayName,
                            String apiModelName, List<String> capabilityCodes,
                            int contextWindow, int maximumOutputTokens, int concurrencyLimit,
                            boolean platformEnabled, boolean userSelectable,
                            boolean applyToAllTasks) {
        String provider = normalizeProvider(providerCode);
        String model = modelCode == null ? "" : modelCode.strip();
        if (!model.matches("[A-Za-z0-9._-]{2,80}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "供应商或模型代号不正确");
        }
        String name = required(displayName, 80, "模型名称或接口模型名称不正确");
        String apiName = required(apiModelName, 120, "模型名称或接口模型名称不正确");
        String capabilities = capabilities(capabilityCodes);
        if (contextWindow < 1024 || contextWindow > 2_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "上下文窗口需在 1024 至 2000000 Token 之间");
        }
        if (maximumOutputTokens < 256 || maximumOutputTokens > contextWindow) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "最大输出需在 256 Token 至上下文窗口之间");
        }
        if (concurrencyLimit < 1 || concurrencyLimit > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "建议并发需在 1 至 1000 之间");
        }
        if (!repository.providerExists(provider)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模型所属供应商不存在");
        }
        ModelCatalogAdminRepository.ModelRecord existing = repository.findModels().stream()
                .filter(item -> provider.equals(item.providerCode()) && model.equals(item.modelCode()))
                .findFirst().orElse(null);
        boolean runtimeConfigChanged = existing != null && (!apiName.equals(existing.apiModelName())
                || !capabilities.equals(String.join(",", codes(existing.capabilityCodes())))
                || contextWindow != existing.contextWindow()
                || maximumOutputTokens != existing.maximumOutputTokens()
                || concurrencyLimit != existing.concurrencyLimit());
        if (runtimeConfigChanged && modelReferenced(provider, model)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该模型仍被当前任务或三档方案引用，请先解除引用再修改运行参数");
        }
        if (!platformEnabled && modelReferenced(provider, model)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "不能停用正在承担平台主任务或回退任务的模型，请先切换路由");
        }
        if (applyToAllTasks) {
            if (!platformEnabled) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "平台当前模型必须允许平台调用");
            }
            if (!providerAvailableForPlatform(provider)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "该供应商尚未启用或没有可用平台密钥，不能设为平台当前模型");
            }
        }

        Instant now = Instant.now();
        int changed = repository.updateModel(provider, model, name, apiName,
                capabilities, contextWindow, maximumOutputTokens, concurrencyLimit,
                platformEnabled, userSelectable, now);
        if (changed == 0) {
            repository.insertModel(provider, model, name, apiName, capabilities, contextWindow,
                    maximumOutputTokens, concurrencyLimit, platformEnabled, userSelectable, now);
        }
        if (applyToAllTasks && !currentValidationAllowsRouting(provider, model)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "当前模型尚无与现配置匹配且覆盖 CHAT、JSON_MODE 的通过记录，不能切换任务路由");
        }
        if (applyToAllTasks && repository.routeAllPlatformTasks(provider, model, now) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "尚未配置任何平台模型任务，不能批量切换");
        }
        operationLog.append("UPDATE_GENERATION_MODEL", "更新推演模型 " + provider + "/" + model
                + (applyToAllTasks ? "，并应用到全部平台任务" : ""));
    }

    private ModelCatalogProviderView providerView(ModelCatalogAdminRepository.ProviderRecord provider,
                                                   List<ModelCatalogAdminRepository.ModelRecord> models,
                                                   Map<ModelKey, TreeSet<String>> tasksByModel,
                                                   Map<ModelKey, TreeSet<String>> primaryTasksByModel,
                                                   Map<ModelKey, ModelCatalogAdminRepository.ValidationRecord> validations,
                                                   Map<ModelKey, List<ModelCatalogAdminRepository.ServicePlanReference>> plansByModel,
                                                   int taskCount) {
        KeyView credential = keyView(provider.platformSecretCiphertext());
        List<ModelCatalogProviderView.ModelView> modelViews = models.stream().map(model -> {
            ModelKey key = new ModelKey(model.providerCode(), model.modelCode());
            List<String> tasks = List.copyOf(tasksByModel.getOrDefault(key, new TreeSet<>()));
            int primaryTaskCount = primaryTasksByModel.getOrDefault(key, new TreeSet<>()).size();
            ModelCatalogAdminRepository.ValidationRecord validation = validations.get(key);
            String verificationStatus = verificationStatus(provider, model, validation);
            List<ModelCatalogProviderView.ServicePlanReferenceView> planReferences = plansByModel
                    .getOrDefault(key, List.of()).stream().map(reference ->
                            new ModelCatalogProviderView.ServicePlanReferenceView(reference.planId(),
                                    reference.versionNumber(), reference.status(), reference.tierCode(),
                                    reference.taskCode(), reference.usage())).toList();
            return new ModelCatalogProviderView.ModelView(model.modelCode(), model.displayName(),
                    model.apiModelName(), codes(model.capabilityCodes()), model.contextWindow(),
                    model.maximumOutputTokens(), model.concurrencyLimit(), model.platformEnabled(),
                    model.userSelectable(), tasks, taskCount > 0 && primaryTaskCount == taskCount,
                    model.configVersion(), verificationStatus,
                    validation == null ? List.of() : codes(validation.capabilityCodes()),
                    validation == null ? null : validation.latencyMs(),
                    validation == null ? null : validation.errorCode(),
                    validation == null ? null : validation.errorSummary(),
                    validation == null ? null : validation.checkedAt().toString(), planReferences,
                    model.updatedAt().toString());
        }).toList();
        return new ModelCatalogProviderView(provider.providerCode(), provider.displayName(), provider.baseUrl(),
                provider.adapterType(), provider.enabled(), provider.userKeyEnabled(), credential.configured(),
                credential.suffix(), credential.state(), provider.configVersion(), provider.updatedAt().toString(),
                taskCount, modelViews);
    }

    private static String verificationStatus(ModelCatalogAdminRepository.ProviderRecord provider,
                                             ModelCatalogAdminRepository.ModelRecord model,
                                             ModelCatalogAdminRepository.ValidationRecord validation) {
        if (validation == null) return "UNVERIFIED";
        if (validation.providerConfigVersion() != provider.configVersion()
                || validation.modelConfigVersion() != model.configVersion()) return "STALE";
        return validation.status();
    }

    private static List<String> codes(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",")).map(String::strip).filter(value -> !value.isBlank())
                .distinct().sorted().toList();
    }

    private static String capabilities(List<String> values) {
        TreeSet<String> normalized = new TreeSet<>();
        if (values != null) {
            values.stream().map(value -> value == null ? "" : value.strip().toUpperCase(Locale.ROOT))
                    .filter(value -> !value.isBlank()).forEach(normalized::add);
        }
        if (!KNOWN_CAPABILITIES.containsAll(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型能力标签包含未知编码");
        }
        return String.join(",", normalized);
    }

    /** 旧任务路由是立即生效的高风险操作，不能由前端确认框绕过配置版本和能力证据校验。 */
    private boolean currentValidationAllowsRouting(String providerCode, String modelCode) {
        ModelCatalogAdminRepository.ProviderRecord provider = repository.findProviders().stream()
                .filter(item -> providerCode.equals(item.providerCode())).findFirst().orElse(null);
        ModelCatalogAdminRepository.ModelRecord model = repository.findModels().stream()
                .filter(item -> providerCode.equals(item.providerCode()) && modelCode.equals(item.modelCode()))
                .findFirst().orElse(null);
        ModelCatalogAdminRepository.ValidationRecord validation = repository.findLatestValidations().stream()
                .filter(item -> providerCode.equals(item.providerCode()) && modelCode.equals(item.modelCode()))
                .findFirst().orElse(null);
        if (provider == null || model == null || validation == null || !"PASSED".equals(validation.status())) {
            return false;
        }
        return provider.configVersion() == validation.providerConfigVersion()
                && model.configVersion() == validation.modelConfigVersion()
                && Set.copyOf(codes(model.capabilityCodes())).containsAll(ROUTING_CAPABILITIES)
                && Set.copyOf(codes(validation.capabilityCodes())).containsAll(ROUTING_CAPABILITIES);
    }

    private boolean providerReferenced(String providerCode) {
        return repository.findEffectiveRouteReferences().stream()
                .anyMatch(reference -> providerCode.equals(reference.providerCode()))
                || repository.findServicePlanReferences().stream()
                .anyMatch(reference -> providerCode.equals(reference.providerCode()));
    }

    private boolean modelReferenced(String providerCode, String modelCode) {
        return repository.findEffectiveRouteReferences().stream()
                .anyMatch(reference -> providerCode.equals(reference.providerCode())
                        && modelCode.equals(reference.modelCode()))
                || repository.findServicePlanReferences().stream()
                .anyMatch(reference -> providerCode.equals(reference.providerCode())
                        && modelCode.equals(reference.modelCode()));
    }

    private boolean providerAvailableForPlatform(String providerCode) {
        return repository.findProviderCredential(providerCode).filter(ModelCatalogAdminRepository.ProviderCredentialRecord::enabled)
                .map(credential -> keyView(credential.ciphertext()).configured()).orElse(false);
    }

    private KeyView keyView(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return new KeyView(false, "", "MISSING");
        try {
            String value = SecretCipher.decrypt(ciphertext, masterKey);
            if (value.isBlank()) return new KeyView(false, "", "MISSING");
            return new KeyView(true, value.substring(Math.max(0, value.length() - 4)), "CONFIGURED");
        } catch (RuntimeException error) {
            return new KeyView(false, "", "DECRYPT_ERROR");
        }
    }

    private static String normalizeProvider(String value) {
        String provider = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!provider.matches("[A-Z0-9_]{2,32}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "供应商代号格式不正确");
        }
        return provider;
    }

    private static String required(String value, int maximumLength, String message) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > maximumLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return normalized;
    }

    private record ModelKey(String providerCode, String modelCode) { }

    private record KeyView(boolean configured, String suffix, String state) { }
}

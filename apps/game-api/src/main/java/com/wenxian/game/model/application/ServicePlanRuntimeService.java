package com.wenxian.game.model.application;

import com.wenxian.game.model.domain.ModelTask;
import com.wenxian.game.model.domain.ServicePlanSelection;
import com.wenxian.game.model.persistence.ModelCatalogRepository;
import com.wenxian.game.model.persistence.ServicePlanRuntimeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 在请求开始时冻结生效方案与玩家档位，并按冻结版本解析任务路由。
 *
 * <p>自备密钥或未开启推演点账本时返回空选择，调用方继续走既有个人/兼容平台路由。
 * 新方案发布只影响之后开始的请求，不会改变已经冻结的会话。</p>
 */
@Service
public class ServicePlanRuntimeService {
    private final ModelCatalogRepository catalog;
    private final ServicePlanRuntimeRepository plans;
    private final boolean enabled;

    public ServicePlanRuntimeService(ModelCatalogRepository catalog, ServicePlanRuntimeRepository plans,
                                     @Value("${wenxian.turn-credit.enabled:false}") boolean enabled) {
        this.catalog = catalog;
        this.plans = plans;
        this.enabled = enabled;
    }

    public Optional<ServicePlanSelection> freezeForUser(String userId) {
        if (!enabled || personal(userId)) return Optional.empty();
        ServicePlanRuntimeRepository.ActivePlan active = plans.findActivePlan().orElse(null);
        if (active == null) return Optional.empty();
        List<ServicePlanRuntimeRepository.Tier> tiers = plans.findEnabledTiers(active.id());
        if (tiers.isEmpty()) throw unavailable("生效推演服务方案没有可用档位");
        String preferred = plans.findUserTierPreference(userId).orElse(active.defaultTierCode());
        ServicePlanRuntimeRepository.Tier selected = tiers.stream()
                .filter(tier -> tier.tierCode().equalsIgnoreCase(preferred))
                .findFirst()
                .orElseGet(() -> tiers.stream()
                        .filter(tier -> tier.tierCode().equalsIgnoreCase(active.defaultTierCode()))
                        .findFirst().orElseThrow(() -> unavailable("生效推演服务方案缺少默认档位")));
        return Optional.of(new ServicePlanSelection(active.id(), selected.tierCode(), selected.pointCost()));
    }

    public List<PlayerTier> playerTiers(String userId) {
        if (!enabled) return List.of();
        ServicePlanRuntimeRepository.ActivePlan active = plans.findActivePlan().orElse(null);
        if (active == null) return List.of();
        List<ServicePlanRuntimeRepository.Tier> tiers = plans.findEnabledTiers(active.id());
        String preferred = plans.findUserTierPreference(userId).orElse(active.defaultTierCode());
        boolean validPreference = tiers.stream().anyMatch(tier -> tier.tierCode().equalsIgnoreCase(preferred));
        String selected = validPreference ? preferred : active.defaultTierCode();
        return tiers.stream().map(tier -> new PlayerTier(tier.tierCode(), tier.displayName(), tier.description(),
                tier.pointCost(), tier.tierCode().equalsIgnoreCase(selected))).toList();
    }

    public void selectTier(String userId, String tierCode) {
        if (!enabled) throw unavailable("三档推演服务尚未启用");
        String normalized = tierCode == null ? "" : tierCode.strip().toUpperCase(Locale.ROOT);
        boolean available = plans.findActivePlan()
                .map(active -> plans.findEnabledTiers(active.id()).stream()
                        .anyMatch(tier -> tier.tierCode().equals(normalized)))
                .orElse(false);
        if (!available) throw unavailable("所选推演档位当前不可用");
        plans.saveUserTierPreference(userId, normalized);
    }

    public ModelCatalogRepository.TaskRoute requireTaskRoute(ServicePlanSelection selection, ModelTask task) {
        if (selection == null) throw new IllegalArgumentException("方案任务路由缺少冻结选择");
        ServicePlanRuntimeRepository.TaskRoute route = plans.findTaskRoute(
                        selection.planVersionId(), selection.tierCode(), task)
                .orElseThrow(() -> unavailable("冻结方案中的模型任务路由不可用"));
        return new ModelCatalogRepository.TaskRoute(route.task(), route.primaryProviderCode(),
                route.primaryModelCode(), route.fallbackProviderCode(), route.fallbackModelCode(),
                route.options(), route.fallbackEnabled());
    }

    private boolean personal(String userId) {
        return catalog.findUserPreference(userId)
                .map(preference -> "USER".equalsIgnoreCase(preference.credentialSource()))
                .orElse(false);
    }

    private ModelTaskRouter.ModelRoutingException unavailable(String message) {
        return new ModelTaskRouter.ModelRoutingException("SERVICE_PLAN_UNAVAILABLE", message);
    }

    /** 玩家可见的档位信息；不得包含供应商或模型编码。 */
    public record PlayerTier(String code, String name, String description, int pointCost, boolean selected) { }
}

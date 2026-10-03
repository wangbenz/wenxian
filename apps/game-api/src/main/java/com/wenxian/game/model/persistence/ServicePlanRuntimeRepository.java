package com.wenxian.game.model.persistence;

import com.wenxian.game.model.domain.ModelInvocationOptions;
import com.wenxian.game.model.domain.ModelTask;

import java.util.List;
import java.util.Optional;

/**
 * 已发布三档推演方案的运行时只读边界，以及账号稳定档位偏好的写入边界。
 *
 * <p>已发布方案不可原地修改，因此请求先读取版本和档位后，可继续按版本 ID 解析任务路由。
 * 仓储不判断玩家是否使用自备密钥，也不负责扣点或调用模型。</p>
 */
public interface ServicePlanRuntimeRepository {
    Optional<ActivePlan> findActivePlan();

    List<Tier> findEnabledTiers(String planVersionId);

    Optional<String> findUserTierPreference(String userId);

    void saveUserTierPreference(String userId, String tierCode);

    Optional<TaskRoute> findTaskRoute(String planVersionId, String tierCode, ModelTask task);

    record ActivePlan(String id, String defaultTierCode) { }

    record Tier(String tierCode, String displayName, String description, int pointCost) { }

    record TaskRoute(ModelTask task, String primaryProviderCode, String primaryModelCode,
                     String fallbackProviderCode, String fallbackModelCode,
                     ModelInvocationOptions options, boolean fallbackEnabled) { }
}

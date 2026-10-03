package com.wenxian.admin.plan;

import java.math.BigDecimal;
import java.util.List;

/** 管理台读取的完整方案版本；发布阻断原因由服务端按当前模型证据实时计算。 */
public record ServicePlanView(
        String id,
        long versionNumber,
        String status,
        long revision,
        String defaultTierCode,
        String changeNote,
        String basedOnVersionId,
        String createdBy,
        String createdAt,
        String publishedBy,
        String publishedAt,
        String retiredAt,
        boolean publishable,
        List<String> blockingReasons,
        boolean gameRuntimeApplied,
        List<TierView> tiers,
        List<TaskRouteView> taskRoutes,
        List<ModelSnapshotView> modelSnapshots
) {
    /** 固定三档中的一个档位；模型引用使用服务端稳定编码，不暴露 API 密钥。 */
    public record TierView(String tierCode, String displayName, String description, int pointCost,
                           boolean enabled, String primaryProviderCode, String primaryModelCode,
                           String fallbackProviderCode, String fallbackModelCode, boolean fallbackEnabled) { }

    /** 某档位针对稳定模型任务的可选覆盖；无覆盖时使用该档位默认模型。 */
    public record TaskRouteView(String tierCode, String taskCode, String primaryProviderCode,
                                String primaryModelCode, String fallbackProviderCode, String fallbackModelCode,
                                BigDecimal temperature, int timeoutSeconds, int maximumRetries,
                                Integer maximumOutputTokens, boolean disableThinking, boolean fallbackEnabled) { }

    /** 发布时固化的模型配置版本与验证证据；草稿在正式发布前不产生证据快照。 */
    public record ModelSnapshotView(String providerCode, String modelCode, long providerConfigVersion,
                                    long modelConfigVersion, String validationRunId, String capturedAt) { }
}

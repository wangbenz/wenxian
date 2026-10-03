package com.wenxian.admin.model;

import java.util.List;

/**
 * 管理台模型库的脱敏读取模型。
 *
 * <p>该 DTO 只暴露密钥状态和末四位，不包含密文或明文。任务编码用于解释模型为何不能停用，
 * 后续三档方案引用也会汇总到同一类“使用中”信息中。</p>
 */
public record ModelCatalogProviderView(
        String providerCode,
        String displayName,
        String baseUrl,
        String adapterType,
        boolean enabled,
        boolean userKeyEnabled,
        boolean keyConfigured,
        String keySuffix,
        String keyState,
        long configVersion,
        String updatedAt,
        int platformTaskCount,
        List<ModelView> models
) {
    /** 单个模型的管理台展示数据，不承载运行时密钥或模型调用参数。 */
    public record ModelView(
            String modelCode,
            String displayName,
            String apiModelName,
            List<String> capabilityCodes,
            int contextWindow,
            int maximumOutputTokens,
            int concurrencyLimit,
            boolean platformEnabled,
            boolean userSelectable,
            List<String> activeTaskCodes,
            boolean allPlatformTasks,
            long configVersion,
            String verificationStatus,
            List<String> verifiedCapabilityCodes,
            Integer verificationLatencyMs,
            String verificationErrorCode,
            String verificationErrorSummary,
            String verifiedAt,
            List<ServicePlanReferenceView> servicePlanReferences,
            String updatedAt
    ) { }

    /** 草稿与生效方案中的引用位置，用于解释模型为何暂时不能修改运行参数或停用。 */
    public record ServicePlanReferenceView(
            String planId,
            long versionNumber,
            String status,
            String tierCode,
            String taskCode,
            String usage
    ) { }
}

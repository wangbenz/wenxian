package com.wenxian.admin.plan;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 三档推演服务方案的持久化边界。
 *
 * <p>仓储只负责版本、档位和任务覆盖项的原子读写；草稿结构、模型验证证据、发布条件与回滚语义
 * 均由应用服务裁决。已经发布的聚合不会通过本仓储原地改写。</p>
 */
public interface ServicePlanAdminRepository {

    List<PlanVersionRecord> findVersions();

    Optional<PlanVersionRecord> findVersion(String planId);

    Optional<PlanVersionRecord> lockVersion(String planId);

    List<TierRecord> findTiers(String planId);

    List<TaskRouteRecord> findTaskRoutes(String planId);

    List<ModelSnapshotRecord> findModelSnapshots(String planId);

    List<String> findSupportedTaskCodes();

    long allocateVersionNumber();

    void lockPublicationSequence();

    void insertVersion(PlanVersionRecord version);

    void insertTiers(String planId, List<TierRecord> tiers);

    void insertTaskRoutes(String planId, List<TaskRouteRecord> routes);

    void replaceModelSnapshots(String planId, List<ModelSnapshotRecord> snapshots);

    void replaceDraftAggregate(String planId, long expectedRevision, String defaultTierCode,
                               String changeNote, List<TierRecord> tiers, List<TaskRouteRecord> routes);

    int retireActive(Instant retiredAt);

    int activateDraft(String planId, long expectedRevision, String publishedBy, Instant publishedAt);

    record PlanVersionRecord(String id, long versionNumber, String status, long revision,
                             String defaultTierCode, String changeNote, String basedOnVersionId,
                             String createdBy, Instant createdAt, String publishedBy,
                             Instant publishedAt, Instant retiredAt) { }

    record TierRecord(String tierCode, String displayName, String description, int pointCost,
                      boolean enabled, String primaryProviderCode, String primaryModelCode,
                      String fallbackProviderCode, String fallbackModelCode, boolean fallbackEnabled) { }

    record TaskRouteRecord(String tierCode, String taskCode, String primaryProviderCode,
                           String primaryModelCode, String fallbackProviderCode, String fallbackModelCode,
                           BigDecimal temperature, int timeoutSeconds, int maximumRetries,
                           Integer maximumOutputTokens, boolean disableThinking, boolean fallbackEnabled) { }

    record ModelSnapshotRecord(String providerCode, String modelCode, long providerConfigVersion,
                               long modelConfigVersion, String validationRunId, Instant capturedAt) { }
}

package com.wenxian.admin.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 数据生命周期管理页的只读契约。
 *
 * <p>队列只在账号仍存在时返回内部 userId；匿名事件、运行记录和删除审计不返回邮箱、账号摘要、
 * 剧情、密钥、邮件正文或原始异常。JSON 只投影固定允许的计数与活动来源字段。</p>
 */
public final class AccountLifecycleViews {
    private AccountLifecycleViews() { }

    public record Overview(
            Map<String, Long> retentionStates,
            Map<String, Long> noticeStates,
            Map<String, Long> deletionJobStates,
            long dueWithin24Hours,
            long dueWithin7Days,
            long unresolvedModelUsage
    ) { }

    public record Page<T>(long total, int page, int size, List<T> rows) { }

    public record NoticeItem(
            String id,
            String userId,
            String status,
            Instant scheduledDeletionAt,
            int attemptCount,
            Instant nextAttemptAt,
            String lastErrorCode,
            Instant updatedAt
    ) { }

    public record DeletionJobItem(
            String id,
            String userId,
            String reason,
            String status,
            int attemptCount,
            String lastErrorCode,
            Instant requestedAt,
            Instant updatedAt,
            Instant completedAt
    ) { }

    public record DeletionAuditItem(
            String id,
            String reason,
            Map<String, Integer> deleteCounts,
            boolean countsReadable,
            Instant completedAt
    ) { }

    public record LifecycleEventItem(
            String id,
            String eventType,
            String reason,
            String referenceId,
            String errorCode,
            Integer attemptCount,
            String activitySource,
            Map<String, Integer> deleteCounts,
            boolean metadataReadable,
            Instant occurredAt
    ) { }

    public record RuntimeStatus(boolean observed, RetentionRun latestRun) { }

    public record RetentionRun(
            String id,
            String triggerSource,
            String status,
            boolean policyEvaluationEnabled,
            boolean notificationDeliveryEnabled,
            boolean automaticDeletionEnabled,
            boolean mailGatewayReady,
            int noticeAfterDays,
            int deleteAfterDays,
            int minimumNoticeDays,
            int batchSize,
            int noticesPrepared,
            int noticesDelivered,
            int deletionJobsPrepared,
            String errorCode,
            Instant startedAt,
            Instant finishedAt
    ) { }
}

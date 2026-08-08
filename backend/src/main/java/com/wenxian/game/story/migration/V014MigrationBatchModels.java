package com.wenxian.game.story.migration;

import com.wenxian.game.story.migration.StoryMigrationOperationPolicy.PolicySnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** V0.14 批量迁移中心使用的精简模型；不向管理台返回完整剧情和命卷 JSON。 */
public final class V014MigrationBatchModels {
    private V014MigrationBatchModels() {}

    /** 迁移中心总览。 */
    public record MigrationOverview(
            long sourceTotal,
            long unprocessed,
            Map<String, Long> statusCounts,
            PolicySnapshot policy
    ) {}

    /** 单份命卷的预演摘要。 */
    public record PreviewItem(
            String sourceSaveId,
            String targetSaveId,
            String previewStatus,
            String migrationStatus,
            int turns,
            int entities,
            int memories,
            List<String> issueCodes,
            String beforeHash,
            String afterHash,
            String error
    ) {}

    /** 带稳定游标的一页预演结果。 */
    public record PreviewPage(
            String cursor,
            String nextCursor,
            int requestedLimit,
            List<PreviewItem> items
    ) {}

    /** 一次有限批量准备请求。 */
    public record PrepareRequest(List<String> sourceSaveIds) {}

    /** 单条激活请求必须携带管理台刚刚核对过的双哈希。 */
    public record ActivateRequest(String sourceSaveId, String expectedBeforeHash, String expectedAfterHash) {}

    /** 管理台更新两个写开关和每次操作上限。 */
    public record PolicyUpdateRequest(boolean prepareEnabled, boolean activationEnabled, int batchLimit) {}

    /** 批量操作中的单份结果；一份失败不会掩盖其他命卷的结果。 */
    public record OperationItem(
            String sourceSaveId,
            boolean success,
            String stage,
            String targetSaveId,
            String beforeHash,
            String afterHash,
            String message
    ) {}

    /** 批量准备的汇总结果。 */
    public record BatchOperationResult(int requested, int succeeded, int failed, List<OperationItem> items) {}

    /** 最近迁移任务的管理台列表项。 */
    public record MigrationTaskItem(
            String sourceSaveId,
            String targetSaveId,
            String status,
            String beforeHash,
            String afterHash,
            String errorMessage,
            Instant startedAt,
            Instant completedAt
    ) {}
}

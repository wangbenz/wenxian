package com.wenxian.game.story.migration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** V0.14 全量只读体检的脱敏报告模型，不包含玩家邮箱、剧情正文或完整命卷状态。 */
public final class V014MigrationAuditModels {
    private V014MigrationAuditModels() {}

    /** 单份源命卷的体检摘要。 */
    public record AuditItem(
            String sourceSaveId,
            String status,
            int turns,
            int entities,
            int memories,
            List<String> issueCodes,
            String beforeHash,
            String afterHash,
            String error
    ) {}

    /** 一次数据库一致性快照中的全量体检报告。 */
    public record AuditReport(
            Instant generatedAt,
            long sourceTotal,
            int scanned,
            Map<String, Long> statusCounts,
            Map<String, Long> issueCounts,
            long totalTurns,
            long totalEntities,
            long totalMemories,
            int maxTurnsPerSave,
            int maxEntitiesPerSave,
            long durationMillis,
            List<AuditItem> complexSaves,
            List<AuditItem> items
    ) {}
}

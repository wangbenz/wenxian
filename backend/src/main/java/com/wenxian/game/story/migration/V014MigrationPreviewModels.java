package com.wenxian.game.story.migration;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * V0.14 命卷迁入统一 story 领域前的只读预演模型。
 * 这些对象只用于检查、展示和生成哈希，不代表数据已经写入目标表。
 */
public final class V014MigrationPreviewModels {
    private V014MigrationPreviewModels() {}

    /** 预演结论：可迁移、存在需确认的推定信息，或源数据不完整而不可迁移。 */
    public enum PreviewStatus {
        READY,
        WARNING,
        INVALID
    }

    /** 单项迁移检查结果，code 供管理台筛选，message 供人工理解。 */
    public record MigrationIssue(String severity, String code, String message) {}

    /** 统一实体表的候选记录，字段与目标表中可持久化事实一一对应。 */
    public record TargetEntity(
            String id,
            String type,
            String name,
            String status,
            boolean visible,
            String summary,
            JsonNode attributes,
            int firstTurn,
            int lastChangedTurn
    ) {}

    /** 统一记忆表的候选记录，推定精度通过迁移问题另行报告。 */
    public record TargetMemory(
            String id,
            String type,
            String importance,
            String title,
            String summary,
            int occurredTurn,
            long worldTime,
            String status
    ) {}

    /** 旧回合转换后的不可变目标回合，包含正式落库需要的全部字段。 */
    public record TargetTurn(
            String id,
            String requestId,
            int turnNumber,
            String playerAction,
            String narrative,
            JsonNode choices,
            JsonNode statePatch,
            long worldTimeBefore,
            long worldTimeAfter,
            String engineCode,
            int engineVersion,
            String promptVersion,
            String provider,
            String model,
            String credentialSource,
            int promptTokens,
            int completionTokens,
            int cacheHitTokens,
            int cacheMissTokens,
            long latencyMs,
            String releaseVersion,
            Instant createdAt
    ) {}

    /** 旧玩家修正转换后的目标审计记录。 */
    public record TargetCorrection(
            String id,
            String userId,
            String instruction,
            String correctionType,
            JsonNode before,
            JsonNode after,
            Instant createdAt
    ) {}

    /** 预演生成的完整目标快照，也是正式迁移唯一允许写入的候选数据。 */
    public record TargetSnapshot(
            JsonNode coreState,
            List<TargetEntity> entities,
            List<TargetMemory> memories,
            List<TargetTurn> turns,
            List<TargetCorrection> corrections,
            String rollingSummary,
            int summarizedThroughTurn
    ) {}

    /**
     * 单份旧命卷的完整预演报告。beforeHash 对应旧表权威数据，afterHash 对应候选目标快照；
     * 只有 READY 或经人工接受的 WARNING 才能进入未来的正式迁移阶段。
     */
    public record PreviewReport(
            PreviewStatus status,
            String sourceSaveId,
            String targetSaveId,
            String userId,
            int slotNo,
            String sourceStatus,
            int sourceSchemaVersion,
            int targetSchemaVersion,
            long sourceRevision,
            int stateTurnNumber,
            int sourceTurnCount,
            int targetTurnCount,
            Map<String, Integer> entityCounts,
            int memoryCount,
            boolean conversationPresent,
            int correctionCount,
            String beforeHash,
            String afterHash,
            Instant sourceCreatedAt,
            Instant sourceUpdatedAt,
            List<MigrationIssue> issues,
            TargetSnapshot target
    ) {}
}

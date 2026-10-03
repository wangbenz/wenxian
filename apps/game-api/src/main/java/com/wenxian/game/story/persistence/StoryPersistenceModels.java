package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 统一命卷持久化层使用的数据模型。它们只表达数据库事实，不直接作为玩家 API DTO，
 * 也不包含 V0.13、V0.14 等产品发布版本的业务判断。
 */
public final class StoryPersistenceModels {
    private StoryPersistenceModels() {}

    /** 新建统一命卷所需的完整初始快照和来源信息。 */
    public record NewSave(
            String id,
            String userId,
            int slotNo,
            int activeSlotNo,
            String status,
            String worldCode,
            String engineCode,
            int engineVersion,
            int stateSchemaVersion,
            String sourceType,
            String sourceEdition,
            String sourceSaveId,
            String parentSaveId,
            JsonNode state,
            int turnNumber,
            String createdRelease,
            String lastPlayedRelease,
            Instant createdAt
    ) {}

    /** 从统一命卷主表读取的权威核心快照。 */
    public record StoredSave(
            String id,
            String userId,
            int slotNo,
            int activeSlotNo,
            String status,
            String worldCode,
            String engineCode,
            int engineVersion,
            int stateSchemaVersion,
            String sourceType,
            String sourceEdition,
            String sourceSaveId,
            String parentSaveId,
            JsonNode state,
            long revision,
            int turnNumber,
            String createdRelease,
            String lastPlayedRelease,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt,
            String deletedBy,
            String deleteReason
    ) {}

    /**
     * 命卷列表使用的轻量索引记录。它只携带列表展示和生命周期所需字段，不装配实体、记忆或完整运行态；
     * 单份快照损坏时 {@code stateReadable} 为 false，调用方仍可展示和管理该槽位。
     */
    public record StoredSaveIndex(
            String id,
            String userId,
            int activeSlotNo,
            String status,
            String worldCode,
            String engineCode,
            int engineVersion,
            int stateSchemaVersion,
            JsonNode state,
            boolean stateReadable,
            long revision,
            int turnNumber,
            Instant updatedAt
    ) {}

    /** 成功提交后追加到回合事实表的不可变记录。 */
    public record NewTurn(
            String id,
            String saveId,
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
            int stateSchemaVersion,
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

    /** 已持久化的不可变回合事实。 */
    public record StoredTurn(
            String id,
            String saveId,
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
            Integer stateSchemaVersion,
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

    /** 命卷中需要跨回合维持一致的当前实体。 */
    public record Entity(
            String id,
            String saveId,
            String type,
            String name,
            String status,
            boolean visible,
            String summary,
            JsonNode attributes,
            int firstTurn,
            int lastChangedTurn,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /**
     * 命卷实体的长期别名。稳定实体 ID 永远不因改名变化；别名只负责把历史称谓解析回同一实体。
     */
    public record EntityAlias(
            String id,
            String saveId,
            String entityId,
            String aliasName,
            String normalizedAlias,
            String aliasType,
            Integer sourceTurn,
            boolean active,
            Instant createdAt,
            Instant updatedAt
    ) {}
}

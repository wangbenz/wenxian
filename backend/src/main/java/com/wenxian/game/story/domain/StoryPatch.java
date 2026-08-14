package com.wenxian.game.story.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Story Agent 对单回合结果提出的中性变化契约。
 *
 * <p>该对象不是数据库事实。只有经过 {@link StoryPatchValidator}、状态归并器和事务提交后，
 * 其中的变化才会进入正式回合与当前投影。契约不包含产品发布版本，也不定义战力、物价和职业规则。</p>
 */
public record StoryPatch(
        String title,
        String narrative,
        TimePassage time,
        CoreStatePatch coreState,
        List<EntityPatch> entities,
        List<WorldFactPatch> worldFacts,
        List<KnowledgePatch> knowledge,
        List<MemoryDraft> memories,
        DecisionCheckpoint decisionCheckpoint,
        List<ChoiceDraft> choices,
        String rollingSummary
) {
    public StoryPatch {
        entities = safe(entities);
        worldFacts = safe(worldFacts);
        knowledge = safe(knowledge);
        memories = safe(memories);
        choices = safe(choices);
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /** 本轮实际经过时间；中断时必须说明原因，不能让正文与时间线各写一套结果。 */
    public record TimePassage(long elapsedMinutes, TimeResolution resolution, String interruptionReason) {}

    public enum TimeResolution {
        COMPLETE,
        INTERRUPTED
    }

    /** 只表达主角核心快照的可选变化，不携带实体集合。 */
    public record CoreStatePatch(String location, String condition, String realm, String lifeGoal) {}

    /**
     * 实体变化必须通过稳定 ID 关联旧实体。`CREATE` 不携带正式 ID，由服务端提交时分配；
     * 其他操作必须引用本轮上下文中提供的实体 ID。
     */
    public record EntityPatch(
            EntityOperation operation,
            String entityId,
            String entityType,
            String name,
            String status,
            String summary,
            JsonNode attributes,
            QuantityValue quantity,
            Boolean inPossession,
            CustodyValue custody,
            AbilityState abilityState,
            LifecycleValue lifecycle,
            RelationshipValue relationship,
            String referenceKey
    ) {
        public EntityPatch {
            referenceKey = referenceKey == null ? "" : referenceKey.strip();
        }

        /** 兼容回合内引用键加入前的完整实体补丁。 */
        public EntityPatch(EntityOperation operation, String entityId, String entityType, String name,
                           String status, String summary, JsonNode attributes, QuantityValue quantity,
                           Boolean inPossession, CustodyValue custody, AbilityState abilityState,
                           LifecycleValue lifecycle, RelationshipValue relationship) {
            this(operation, entityId, entityType, name, status, summary, attributes, quantity,
                    inPossession, custody, abilityState, lifecycle, relationship, "");
        }

        /** 兼容尚未显式提供持有者的旧提案和测试；新模型提案应优先填写 custody。 */
        public EntityPatch(EntityOperation operation, String entityId, String entityType, String name,
                           String status, String summary, JsonNode attributes, QuantityValue quantity,
                           Boolean inPossession, AbilityState abilityState, LifecycleValue lifecycle) {
            this(operation, entityId, entityType, name, status, summary, attributes, quantity,
                    inPossession, null, abilityState, lifecycle, null, "");
        }

        /** 兼容已经显式提供物品保管关系、但尚未提供人物关系字段的调用方。 */
        public EntityPatch(EntityOperation operation, String entityId, String entityType, String name,
                           String status, String summary, JsonNode attributes, QuantityValue quantity,
                           Boolean inPossession, CustodyValue custody, AbilityState abilityState,
                           LifecycleValue lifecycle) {
            this(operation, entityId, entityType, name, status, summary, attributes, quantity,
                    inPossession, custody, abilityState, lifecycle, null, "");
        }
    }

    public enum EntityOperation {
        CREATE,
        UPDATE,
        HIDE,
        RESTORE
    }

    /** `known=false` 表示只确认持有但数量未知，此时 value 必须为空。 */
    public record QuantityValue(boolean known, Integer value) {}

    /**
     * 物品当前由谁实际保管。PLAYER 不需要 holderRef；CHARACTER、LOCATION 必须引用稳定实体或地点标识。
     * UNKNOWN 只用于兼容无法还原保管人的旧记录，不能作为一次新交易的目标。
     */
    public record CustodyValue(HolderType holderType, String holderRef) {}

    public enum HolderType {
        PLAYER,
        CHARACTER,
        LOCATION,
        NONE,
        UNKNOWN
    }

    /** 能力是否仍然掌握与能否临时施展分开表达，不再从自然语言状态猜测。 */
    public enum AbilityState {
        MASTERED,
        TEMPORARILY_BLOCKED,
        LOST,
        UNKNOWN
    }

    /** 人物客观生命周期；终态被反转时必须记录明确原因。 */
    public record LifecycleValue(LifecycleState state, String changeReason) {}

    public enum LifecycleState {
        ACTIVE,
        MISSING,
        DORMANT,
        DEAD,
        UNKNOWN
    }

    /**
     * 主角与重要人物当前已经确认的关系。label 保持叙事自由，不引入好感度数值；已有关系发生改变时，
     * changeReason 必须说明本轮发生了什么，供独立审阅与回放使用。
     */
    public record RelationshipValue(String label, String changeReason) {}

    /** 世界扩展、世界内转变与玩家显式修订使用同一审计契约。 */
    public record WorldFactPatch(
            WorldFactOperation operation,
            String factId,
            String statement,
            String reason
    ) {}

    public enum WorldFactOperation {
        EXPAND,
        TRANSFORM,
        REVISE
    }

    /** 人物认知必须标明主体和依据，不能直接覆盖客观事实。 */
    public record KnowledgePatch(
            String subjectRef,
            String topicRef,
            String summary,
            List<String> evidenceRefs
    ) {
        public KnowledgePatch {
            evidenceRefs = safe(evidenceRefs);
        }
    }

    /** 跨回合仍需取回的客观事件、承诺或线索摘要。 */
    public record MemoryDraft(
            String memoryType,
            String title,
            String summary,
            String importance,
            List<String> evidenceRefs
    ) {
        public MemoryDraft {
            evidenceRefs = safe(evidenceRefs);
        }
    }

    /** 当剧情来到新的重大、不可逆决定时，要求 Agent 停止并把决定交还玩家。 */
    public record DecisionCheckpoint(boolean required, String prompt) {}

    public record ChoiceDraft(String title, String description) {}
}

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
        String rollingSummary,
        TurnOutcome outcome,
        CultivationPatch cultivation
) {
    public StoryPatch {
        entities = safe(entities);
        worldFacts = safe(worldFacts);
        knowledge = safe(knowledge);
        memories = safe(memories);
        choices = safe(choices);
    }

    /** 兼容结果字段加入前的旧调用方和历史回合。 */
    public StoryPatch(String title, String narrative, TimePassage time, CoreStatePatch coreState,
                      List<EntityPatch> entities, List<WorldFactPatch> worldFacts,
                      List<KnowledgePatch> knowledge, List<MemoryDraft> memories,
                      DecisionCheckpoint decisionCheckpoint, List<ChoiceDraft> choices,
                      String rollingSummary) {
        this(title, narrative, time, coreState, entities, worldFacts, knowledge, memories,
                decisionCheckpoint, choices, rollingSummary, null, null);
    }

    /** 兼容结构化修行变化加入前的普通回合、修正和固定回放。 */
    public StoryPatch(String title, String narrative, TimePassage time, CoreStatePatch coreState,
                      List<EntityPatch> entities, List<WorldFactPatch> worldFacts,
                      List<KnowledgePatch> knowledge, List<MemoryDraft> memories,
                      DecisionCheckpoint decisionCheckpoint, List<ChoiceDraft> choices,
                      String rollingSummary, TurnOutcome outcome) {
        this(title, narrative, time, coreState, entities, worldFacts, knowledge, memories,
                decisionCheckpoint, choices, rollingSummary, outcome, null);
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * 本轮实际经过时间。
     *
     * <p>{@code duration} 是模型协议、Reducer 和可信事件共同使用的唯一时间表示。
     * 普通场景不必改变世界时钟；长期行动完整执行时必须保留玩家授权的语义时长。</p>
     */
    public record TimePassage(SemanticDuration duration, TimeResolution resolution,
                              String interruptionReason) {}

    public enum TimeResolution {
        COMPLETE,
        INTERRUPTED
    }

    /**
     * 玩家本轮授权行动的直接结果。它与时间是否完整执行分开：行动可以完整执行但失败，也可以只取得部分进展。
     * 旧回合没有该字段时保持为空，不能从历史正文反向推断。
     */
    public record TurnOutcome(OutcomeResolution resolution, String summary) {}

    public enum OutcomeResolution {
        SUCCEEDED,
        PARTIAL,
        FAILED
    }

    /** 只表达主角核心快照的可选变化，不携带实体集合。 */
    public record CoreStatePatch(String location, String condition, String realm, String lifeGoal) {}

    /**
     * 服务端已经裁决的修行结果。目标大境界、小阶段和进度值必须构成完整的回合结束状态；模型原始意图
     * 不能直接构造本对象。CORRECTED 仅供玩家确认后的命卷修正使用，因此允许省略普通推进字段。
     */
    public record CultivationPatch(
            String targetStageId,
            WorldPackage.RealmPhase targetPhase,
            int targetProgressMarks,
            int targetRequiredProgressMarks,
            boolean breakthroughAttempted,
            String progressNote,
            List<String> evidenceRefs,
            CultivationResolution resolution,
            String reason,
            OutcomeResolution actionOutcome
    ) {
        public CultivationPatch {
            evidenceRefs = safe(evidenceRefs);
        }

        /** 旧回合缺少局部结果时沿用整轮校验；不从旧正文反推新的修行子结果。 */
        public CultivationPatch(String targetStageId, WorldPackage.RealmPhase targetPhase,
                                int targetProgressMarks, int targetRequiredProgressMarks,
                                boolean breakthroughAttempted, String progressNote, List<String> evidenceRefs,
                                CultivationResolution resolution, String reason) {
            this(targetStageId, targetPhase, targetProgressMarks, targetRequiredProgressMarks,
                    breakthroughAttempted, progressNote, evidenceRefs, resolution, reason, null);
        }

        /** 玩家确认的修正仍只选择稳定大境界；Reducer 会重置相应的小阶段进度。 */
        public CultivationPatch(String targetStageId, String progressNote,
                                CultivationResolution resolution, String reason) {
            this(targetStageId, null, -1, -1, false, progressNote, List.of(), resolution, reason);
        }
    }

    public enum CultivationResolution {
        UNCHANGED,
        PROGRESSED,
        PHASE_ADVANCED,
        RANK_ADVANCED,
        REJECTED,
        CORRECTED
    }

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
            NameChange nameChange,
            String referenceKey
    ) {
        public EntityPatch {
            referenceKey = referenceKey == null ? "" : referenceKey.strip();
        }

        /** 兼容回合内引用键加入前的完整实体补丁。 */
        public EntityPatch(EntityOperation operation, String entityId, String entityType, String name,
                           String status, String summary, JsonNode attributes, QuantityValue quantity,
                           Boolean inPossession, CustodyValue custody, AbilityState abilityState,
                           LifecycleValue lifecycle, RelationshipValue relationship, String referenceKey) {
            this(operation, entityId, entityType, name, status, summary, attributes, quantity,
                    inPossession, custody, abilityState, lifecycle, relationship, null, referenceKey);
        }

        /** 兼容回合内引用键加入前的完整实体补丁。 */
        public EntityPatch(EntityOperation operation, String entityId, String entityType, String name,
                           String status, String summary, JsonNode attributes, QuantityValue quantity,
                           Boolean inPossession, CustodyValue custody, AbilityState abilityState,
                           LifecycleValue lifecycle, RelationshipValue relationship) {
            this(operation, entityId, entityType, name, status, summary, attributes, quantity,
                    inPossession, custody, abilityState, lifecycle, relationship, null, "");
        }

        /** 兼容尚未显式提供持有者的旧提案和测试；新模型提案应优先填写 custody。 */
        public EntityPatch(EntityOperation operation, String entityId, String entityType, String name,
                           String status, String summary, JsonNode attributes, QuantityValue quantity,
                           Boolean inPossession, AbilityState abilityState, LifecycleValue lifecycle) {
            this(operation, entityId, entityType, name, status, summary, attributes, quantity,
                    inPossession, null, abilityState, lifecycle, null, null, "");
        }

        /** 兼容已经显式提供物品保管关系、但尚未提供人物关系字段的调用方。 */
        public EntityPatch(EntityOperation operation, String entityId, String entityType, String name,
                           String status, String summary, JsonNode attributes, QuantityValue quantity,
                           Boolean inPossession, CustodyValue custody, AbilityState abilityState,
                           LifecycleValue lifecycle) {
            this(operation, entityId, entityType, name, status, summary, attributes, quantity,
                    inPossession, custody, abilityState, lifecycle, null, null, "");
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
        KNOWN,
        LEARNING,
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

    /** 只有玩家明确改名或已完成剧情明确形成新称呼时，才允许改变稳定实体的当前名称。 */
    public record NameChange(boolean explicit, String reason) {}

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

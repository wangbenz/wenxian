package com.wenxian.game.story.agent.ai;

import com.wenxian.game.story.domain.AssetProcess;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/** 回合导演只提出结果与事实意图，不能直接修改数据库。 */
public interface TurnDirectorAi {
    @UserMessage("{{context}}")
    Result<DecisionDraft> direct(@V("context") String context);

    /** 供应商只需生成简单草案；领域枚举、JsonNode 和稳定引用由适配器显式转换并校验。 */
    record DecisionDraft(DurationDraft duration, TimeResolution timeResolution,
                         String interruptionReason, CoreStateDraft coreState, CultivationDraft cultivation,
                         List<ItemDraft> itemChanges, List<EntityDraft> abilityChanges, List<EntityDraft> entities,
                         List<WorldFactDraft> worldFacts, AssetProcessDraft assetProcess,
                         UnderstandingDraft actionUnderstanding,
                         OrderedTurnResult.Proposal orderedResult) {
        /**
         * 仅保留旧 Java 固定样本的构造能力，不从旧摘要伪造有序结果。生成器会拒绝该不完整草案；
         * 成功路径的固定样本必须显式迁移到新 canonical 构造，正常 JSON 不走本兼容入口。
         */
        public DecisionDraft(OutcomeDraft outcome, DurationDraft duration, TimeResolution timeResolution,
                             String interruptionReason, CoreStateDraft coreState, CultivationDraft cultivation,
                             List<ItemDraft> itemChanges, List<EntityDraft> abilityChanges, List<EntityDraft> entities,
                             List<WorldFactDraft> worldFacts, List<MemoryDraft> memories,
                             DecisionCheckpointDraft decisionCheckpoint, List<String> sceneBeats,
                             AssetProcessDraft assetProcess, StoryTurnDecision.ActionUnderstanding understanding) {
            this(duration, timeResolution, interruptionReason, coreState, cultivation, itemChanges, abilityChanges,
                    entities, worldFacts, assetProcess, understanding == null ? null
                            : new UnderstandingDraft(understanding.purpose(), understanding.constraints()), null);
        }

        /** 旧 Java 固定样本构造入口；缺失有序结果不会在模型运行时自动补齐。 */
        public DecisionDraft(OutcomeDraft outcome, DurationDraft duration, TimeResolution timeResolution,
                             String interruptionReason, CoreStateDraft coreState, CultivationDraft cultivation,
                             List<ItemDraft> itemChanges, List<EntityDraft> abilityChanges, List<EntityDraft> entities,
                             List<WorldFactDraft> worldFacts, List<MemoryDraft> memories,
                             DecisionCheckpointDraft decisionCheckpoint, List<String> sceneBeats) {
            this(outcome, duration, timeResolution, interruptionReason, coreState, cultivation,
                    itemChanges, abilityChanges, entities, worldFacts, memories, decisionCheckpoint, sceneBeats, null, null);
        }

        /** 仅供旧 Java 固定样本构造；模型 JSON 使用包含三组变化的完整协议。 */
        public DecisionDraft(OutcomeDraft outcome, DurationDraft duration, TimeResolution timeResolution,
                             String interruptionReason, CoreStateDraft coreState, CultivationDraft cultivation,
                             List<EntityDraft> entities, List<WorldFactDraft> worldFacts, List<MemoryDraft> memories,
                             DecisionCheckpointDraft decisionCheckpoint, List<String> sceneBeats) {
            this(outcome, duration, timeResolution, interruptionReason, coreState, cultivation,
                    entities.stream().filter(e -> "ITEM".equals(e.entityType())).map(ItemDraft::fromLegacyFixture).toList(),
                    entities.stream().filter(e -> "ABILITY".equals(e.entityType())).toList(),
                    entities.stream().filter(e -> !"ITEM".equals(e.entityType()) && !"ABILITY".equals(e.entityType())).toList(),
                    worldFacts, memories, decisionCheckpoint, sceneBeats);
        }

        /** 兼容结构化修行意图加入前的测试与固定样本。 */
        public DecisionDraft(OutcomeDraft outcome, DurationDraft duration, TimeResolution timeResolution,
                             String interruptionReason, CoreStateDraft coreState, List<EntityDraft> entities,
                             List<WorldFactDraft> worldFacts, List<MemoryDraft> memories,
                             DecisionCheckpointDraft decisionCheckpoint, List<String> sceneBeats) {
            this(outcome, duration, timeResolution, interruptionReason, coreState, null, entities,
                    worldFacts, memories, decisionCheckpoint, sceneBeats);
        }
    }

    record OutcomeDraft(OutcomeResolution resolution, String summary) {}

    /** 模型理解只保留目的与限制；本轮发生什么统一在 orderedResult 中表达，不再复制一份步骤结果。 */
    record UnderstandingDraft(String purpose, List<String> constraints) {}

    record DurationDraft(DurationUnit unit, int value, String targetPhase) {}

    record CoreStateDraft(String location, String condition, String realm) {}

    /** 只表达一枚进展与明确突破尝试；目标阶段和数值全部由服务端裁决。 */
    record CultivationDraft(boolean progressGained, boolean breakthroughAttempted,
                            String reason, List<String> evidenceRefs) {}

    /**
     * 模型边界的可选枚举字段使用字符串承接供应商常见的空串噪声；适配器会在明确存在值时再转为领域枚举。
     * 必填的行动结果、时间单位和时间结果仍保持强类型，不能以宽输入绕过核心回合协议。
     */
    record EntityDraft(String existingEntityId, String localRef, String entityType, String name,
                       String status, String summary, String scenePresence, List<AttributeDraft> attributes,
                       QuantityDraft quantity, CustodyDraft custody, String abilityState,
                       LifecycleDraft lifecycle, RelationshipDraft relationship, Boolean visible) {}

    /** 物品模型草案只含元数据，不向模型暴露任何最终余额、保管或人物字段。 */
    record ItemDraft(String existingEntityId, String localRef, String name, String status, String summary,
                     List<AttributeDraft> attributes, Boolean visible) {
        /** 仅转换旧 Java 构造样本的元数据；数量不会成为第二条模型写入路径。 */
        static ItemDraft fromLegacyFixture(EntityDraft entity) {
            return new ItemDraft(entity.existingEntityId(), entity.localRef(), entity.name(), entity.status(),
                    entity.summary(), entity.attributes(), entity.visible());
        }
    }

    /** 模型只填写有序过程；固定内部版本和步骤引用由服务器生成。 */
    record AssetProcessDraft(List<AssetStepDraft> steps) {}

    record AssetStepDraft(AssetProcess.Kind kind, String summary, List<FlowDraft> inputs,
                          List<FlowDraft> outputs, List<ObservationDraft> observations) {}

    /** 数量是受限数字字面量或 ALL/UNKNOWN；不是可由 Java 解释的中文短句。 */
    record FlowDraft(String entityRef,
                     @JsonDeserialize(using = DirectorAssetDraftAdapter.StrictAmountDeserializer.class) String amount,
                     String unit, CustodyDraft custody) {}

    /** 盘点保留原有独立数量规则和发生顺序；不能作为交易或生产的余额覆盖入口。 */
    record ObservationDraft(String entityRef, QuantityDraft quantity, String unit,
                            CustodyDraft custody, String evidence) {}

    record AttributeDraft(String key, String value) {}

    record QuantityDraft(boolean known, Integer value) {}

    record CustodyDraft(String holderType, String holderRef, String localHolderRef) {}

    record LifecycleDraft(String state, String changeReason) {}

    record RelationshipDraft(String label, String changeReason) {}

    record WorldFactDraft(String factId, String statement, String reason) {}

    record MemoryDraft(String memoryType, String title, String summary,
                       String importance, List<String> evidenceRefs) {}

    record DecisionCheckpointDraft(boolean required, String prompt) {}

    enum OutcomeResolution { SUCCEEDED, PARTIAL, FAILED }

    enum DurationUnit { SCENE, PHASE, DAY, MONTH, YEAR }

    enum TimeResolution { COMPLETE, INTERRUPTED }

}

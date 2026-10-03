package com.wenxian.game.story.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.RelationshipValue;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.worldpackage.domain.WorldContext;

import java.util.List;

/**
 * 提供给故事模型的中性、分区上下文。
 *
 * <p>客观事实、人物认知、历史叙事和本轮行动分别存放，模型适配器只能序列化这些分区，
 * 不能把摘要或人物观点重新标记为客观事实。该对象不包含模型供应商和发布版本。</p>
 */
public record StoryPromptContext(
        SystemContext system,
        ObjectiveContext objective,
        CognitionContext cognition,
        MemoryContext memory,
        NarrativeContext narrative,
        ActionContext action,
        WorldContext worldContext
) {
    /** 非世界叙事任务可不携带世界资料；正式回合由准备服务显式附加，不能从系统指令推导。 */
    public StoryPromptContext(SystemContext system, ObjectiveContext objective, CognitionContext cognition,
                              MemoryContext memory, NarrativeContext narrative, ActionContext action) {
        this(system, objective, cognition, memory, narrative, action, null);
    }

    /** 世界资料为一次准备内不可变的数据快照，不写入命卷状态，也不提升为系统指令。 */
    public StoryPromptContext withWorldContext(WorldContext world) {
        if (world == null) throw new IllegalArgumentException("正式回合缺少世界资料快照");
        return new StoryPromptContext(system, objective, cognition, memory, narrative, action, world);
    }

    public record SystemContext(List<String> invariants, List<String> engineRules) {
        public SystemContext {
            invariants = safe(invariants);
            engineRules = safe(engineRules);
        }
    }

    public record ObjectiveContext(
            StoryWorldTime worldTime,
            CoreState protagonist,
            List<WorldFactView> worldFacts,
            List<EntityIndexEntry> entityIndex,
            List<EntityDetail> relatedEntities,
            InventoryView currentInventory,
            PendingDecision pendingDecision,
            CreationContractView creationContract,
            PlayPreferenceView playPreference,
            List<SourceExcerpt> sourceExcerpts,
            CultivationView cultivation
    ) {
        public ObjectiveContext(StoryWorldTime worldTime, CoreState protagonist,
                                List<WorldFactView> worldFacts, List<EntityIndexEntry> entityIndex,
                                List<EntityDetail> relatedEntities) {
            this(worldTime, protagonist, worldFacts, entityIndex, relatedEntities,
                    new InventoryView(true, List.of(), List.of()), null, null, null, List.of(), null);
        }

        public ObjectiveContext(StoryWorldTime worldTime, CoreState protagonist,
                                List<WorldFactView> worldFacts, List<EntityIndexEntry> entityIndex,
                                List<EntityDetail> relatedEntities, PendingDecision pendingDecision) {
            this(worldTime, protagonist, worldFacts, entityIndex, relatedEntities,
                    new InventoryView(true, List.of(), List.of()), pendingDecision,
                    null, null, List.of(), null);
        }

        public ObjectiveContext(StoryWorldTime worldTime, CoreState protagonist,
                                List<WorldFactView> worldFacts, List<EntityIndexEntry> entityIndex,
                                List<EntityDetail> relatedEntities, InventoryView currentInventory,
                                PendingDecision pendingDecision) {
            this(worldTime, protagonist, worldFacts, entityIndex, relatedEntities,
                    currentInventory, pendingDecision, null, null, List.of(), null);
        }

        public ObjectiveContext {
            if (worldTime == null) throw new IllegalArgumentException("模型上下文缺少语义世界时间");
            worldFacts = safe(worldFacts);
            entityIndex = safe(entityIndex);
            relatedEntities = safe(relatedEntities);
            currentInventory = currentInventory == null
                    ? new InventoryView(true, List.of(), List.of()) : currentInventory;
            sourceExcerpts = safe(sourceExcerpts);
        }
    }

    /**
     * 命卷级主修体系的完整权威视图。模型只能据此理解当前修行位置并提出一枚进展，不能选择目标状态；
     * 显示名称和别称也不能成为新的境界身份。
     */
    public record CultivationView(
            String systemId,
            int version,
            String name,
            String summary,
            String currentStageId,
            String currentStageName,
            String currentRankKey,
            String currentPhase,
            int progressMarks,
            int requiredProgressMarks,
            String progressNote,
            List<CultivationStageView> stages
    ) {
        public CultivationView {
            stages = safe(stages);
        }
    }

    /** 已确认修行体系中的一个稳定大境界。 */
    public record CultivationStageView(
            String stageId,
            int order,
            String name,
            String meaning,
            List<String> aliases,
            List<String> allowedNextStageIds
    ) {
        public CultivationStageView {
            aliases = safe(aliases);
            allowedNextStageIds = safe(allowedNextStageIds);
        }
    }

    /** 创建命卷时由玩家确认的世界、身份与根源，只读且始终进入客观上下文。 */
    public record CreationContractView(
            String worldPackageId, long worldRevision, String worldName, String worldPremise,
            String protagonistName, String genderCode, String raceId, String raceName,
            String originSummary, List<OriginFactView> establishedFacts, List<StoryHookView> openHooks,
            List<String> startingEntityRefs, String openingRegionRef, String openingSituation,
            String realmScaleId, long realmScaleRevision, String startingRankKey, String startingPhase
    ) {
        public CreationContractView {
            establishedFacts = safe(establishedFacts);
            openHooks = safe(openHooks);
            startingEntityRefs = safe(startingEntityRefs);
        }
    }

    public record OriginFactView(String factId, String type, String statement) {}

    public record StoryHookView(String hookId, String summary) {}

    /** 玩家当前游玩偏好，只影响内容选择与提示，不代表已授权行动。 */
    public record PlayPreferenceView(List<String> focus, String combatFrequency, String pace,
                                     String guidance, String additionalNote) {
        public PlayPreferenceView {
            focus = safe(focus);
        }
    }

    /** 当前行动可引用的已提交来源；已出现不等于必须晋升为永久实体。 */
    public record SourceExcerpt(String ref, String sourceType, String title, String content) {}

    /**
     * 当前行囊的完整权威视图。它与包含历史实体的 entityIndex 分离，避免旧正文把已经失去、售出或
     * 主动移除的物品重新叙述成当前持有。complete=true 且 items 为空，明确表示当前没有物品。
     */
    public record InventoryView(boolean complete, List<EntityIndexEntry> items,
                                List<EntityIndexEntry> historicalItems) {
        public InventoryView {
            items = safe(items);
            historicalItems = safe(historicalItems);
        }
    }

    /** 只包含人物知道或相信的内容，不能提升为世界事实。 */
    public record CognitionContext(List<KnowledgeView> entries) {
        public CognitionContext {
            entries = safe(entries);
        }
    }

    /** 关键记忆用于找回长期经历，但仍需通过 evidenceRefs 回到已提交事实。 */
    public record MemoryContext(List<MemoryView> entries) {
        public MemoryContext {
            entries = safe(entries);
        }
    }

    /**
     * 累计摘要与近期回合只是叙事材料，不是权威状态来源。
     *
     * <p>previousTurn 保留给冻结的旧链路；recentTurns 是按时间升序排列的近期窗口，供统一协议维持
     * 场景连续性。两者不得覆盖 objective 中的事实。</p>
     */
    public record NarrativeContext(String rollingSummary, PreviousTurn previousTurn,
                                   List<PreviousTurn> recentTurns) {
        public NarrativeContext(String rollingSummary, PreviousTurn previousTurn) {
            this(rollingSummary, previousTurn,
                    previousTurn == null ? List.of() : List.of(previousTurn));
        }

        public NarrativeContext {
            rollingSummary = rollingSummary == null ? "" : rollingSummary.strip();
            recentTurns = safe(recentTurns);
        }
    }

    /**
     * 玩家行动作为数据单独传递，不能覆盖 SystemContext。语义时长是普通回合唯一的时间意图。
     */
    public record ActionContext(String playerAction, SelectedChoice selectedChoice,
                                SemanticDuration semanticDuration) {
        public ActionContext(String playerAction) {
            this(playerAction, null, null);
        }
    }

    /** 玩家本轮点击的服务端选项；ID 用于证明行动来自当前修订，文字仍只是授权建议。 */
    public record SelectedChoice(String id, String title, String description) {}

    public record WorldFactView(String id, String statement, int revision) {}

    /** 当前剧情已经停下并等待玩家亲自回答的决定；为空表示没有待确认事项。 */
    public record PendingDecision(String prompt) {}

    /** 轻量实体索引；物品计数单位来自已保存的过程口径，旧记录未知时为空，不从名称推断。 */
    public record EntityIndexEntry(
            String id,
            String entityType,
            String name,
            String status,
            boolean visible,
            QuantityValue quantity,
            Boolean inPossession,
            CustodyValue custody,
            AbilityState abilityState,
            LifecycleState lifecycle,
            RelationshipValue relationship,
            String quantityUnit
    ) {
        public EntityIndexEntry(String id, String entityType, String name, String status, boolean visible,
                                QuantityValue quantity, Boolean inPossession, CustodyValue custody,
                                AbilityState abilityState, LifecycleState lifecycle, RelationshipValue relationship) {
            this(id, entityType, name, status, visible, quantity, inPossession, custody, abilityState,
                    lifecycle, relationship, null);
        }
        public EntityIndexEntry(String id, String entityType, String name, String status, boolean visible,
                                QuantityValue quantity, Boolean inPossession, AbilityState abilityState,
                                LifecycleState lifecycle) {
            this(id, entityType, name, status, visible, quantity, inPossession, null, abilityState, lifecycle, null);
        }

        public EntityIndexEntry(String id, String entityType, String name, String status, boolean visible,
                                QuantityValue quantity, Boolean inPossession, CustodyValue custody,
                                AbilityState abilityState, LifecycleState lifecycle) {
            this(id, entityType, name, status, visible, quantity, inPossession, custody, abilityState,
                    lifecycle, null);
        }
    }

    public record EntityDetail(
            String id,
            String entityType,
            String name,
            String status,
            String summary,
            JsonNode attributes,
            boolean visible,
            QuantityValue quantity,
            Boolean inPossession,
            CustodyValue custody,
            AbilityState abilityState,
            LifecycleState lifecycle,
            RelationshipValue relationship
    ) {
        public EntityDetail(String id, String entityType, String name, String status, String summary,
                            JsonNode attributes, boolean visible, QuantityValue quantity, Boolean inPossession,
                            AbilityState abilityState, LifecycleState lifecycle) {
            this(id, entityType, name, status, summary, attributes, visible, quantity, inPossession,
                    null, abilityState, lifecycle, null);
        }

        public EntityDetail(String id, String entityType, String name, String status, String summary,
                            JsonNode attributes, boolean visible, QuantityValue quantity, Boolean inPossession,
                            CustodyValue custody, AbilityState abilityState, LifecycleState lifecycle) {
            this(id, entityType, name, status, summary, attributes, visible, quantity, inPossession,
                    custody, abilityState, lifecycle, null);
        }
    }

    public record KnowledgeView(
            String subjectRef,
            String topicRef,
            String summary,
            List<String> evidenceRefs
    ) {
        public KnowledgeView {
            evidenceRefs = safe(evidenceRefs);
        }
    }

    public record MemoryView(
            String id,
            String memoryType,
            String title,
            String summary,
            String importance,
            List<String> evidenceRefs
    ) {
        public MemoryView {
            evidenceRefs = safe(evidenceRefs);
        }
    }

    public record PreviousTurn(
            long sequence,
            String playerAction,
            String title,
            String narrative,
            String acceptedPatchSummary,
            com.wenxian.game.story.domain.OrderedTurnResult.Accepted acceptedResult,
            com.wenxian.game.story.domain.StoryRecords.Envelope storyRecords
    ) {
        /** 不可变旧步骤结果只读；不能从旧正文推断新故事记录。 */
        public PreviousTurn(long sequence, String playerAction, String title, String narrative,
                            String acceptedPatchSummary,
                            com.wenxian.game.story.domain.OrderedTurnResult.Accepted acceptedResult) {
            this(sequence, playerAction, title, narrative, acceptedPatchSummary, acceptedResult, null);
        }

        /** 旧回合保持缺失，不从正文或摘要推断有序结果。 */
        public PreviousTurn(long sequence, String playerAction, String title, String narrative,
                            String acceptedPatchSummary) {
            this(sequence, playerAction, title, narrative, acceptedPatchSummary, null);
        }
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}

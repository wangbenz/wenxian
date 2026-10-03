package com.wenxian.game.story.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.AssetProcess;
import com.wenxian.game.story.domain.OrderedTurnResult;

import java.util.List;

/**
 * 轻量导演模型对单回合“发生什么”的语义决定。
 *
 * <p>本对象刻意不包含数据库操作、正式新实体 ID、物品持有冗余字段和完整剧情正文。模型只描述结果与
 * 事实意图，{@code StoryFactIntentCompiler} 再根据回合开始前的权威投影生成 StoryPatch。这样模型不需要
 * 学习持久化协议，服务端仍保留稳定 ID、数量、保管和生命周期的最终写入权。</p>
 */
public record StoryTurnDecision(
        StoryPatch.TurnOutcome outcome,
        SemanticDuration duration,
        StoryPatch.TimeResolution timeResolution,
        String interruptionReason,
        CoreStateIntent coreState,
        CultivationIntent cultivation,
        List<EntityIntent> entities,
        List<WorldFactIntent> worldFacts,
        List<StoryPatch.MemoryDraft> memories,
        StoryPatch.DecisionCheckpoint decisionCheckpoint,
        List<String> sceneBeats,
        AssetProcess.Proposal assetProcess,
        ActionUnderstanding actionUnderstanding,
        OrderedTurnResult.Proposal orderedResult
) {
    /** 仅兼容历史 Java 固定样本；正常模型必须显式提供有序结果，不能由旧摘要推断。 */
    public StoryTurnDecision(StoryPatch.TurnOutcome outcome, SemanticDuration duration,
                             StoryPatch.TimeResolution timeResolution, String interruptionReason,
                             CoreStateIntent coreState, CultivationIntent cultivation, List<EntityIntent> entities,
                             List<WorldFactIntent> worldFacts, List<StoryPatch.MemoryDraft> memories,
                             StoryPatch.DecisionCheckpoint decisionCheckpoint, List<String> sceneBeats,
                             AssetProcess.Proposal assetProcess, ActionUnderstanding actionUnderstanding) {
        this(outcome, duration, timeResolution, interruptionReason, coreState, cultivation, entities,
                worldFacts, memories, decisionCheckpoint, sceneBeats, assetProcess, actionUnderstanding, null);
    }

    /**
     * 兼容历史 Java 固定样本；空资产协议显式走旧转换。正常模型适配器必须强制提交新版协议，
     * 不能利用本构造函数把缺失协议静默降级。
     */
    public StoryTurnDecision(StoryPatch.TurnOutcome outcome, SemanticDuration duration,
                             StoryPatch.TimeResolution timeResolution, String interruptionReason,
                             CoreStateIntent coreState, CultivationIntent cultivation, List<EntityIntent> entities,
                             List<WorldFactIntent> worldFacts, List<StoryPatch.MemoryDraft> memories,
                             StoryPatch.DecisionCheckpoint decisionCheckpoint, List<String> sceneBeats) {
        this(outcome, duration, timeResolution, interruptionReason, coreState, cultivation, entities,
                worldFacts, memories, decisionCheckpoint, sceneBeats, null, null);
    }

    /** 兼容结构化修行意图加入前的测试和固定回放样本。 */
    public StoryTurnDecision(StoryPatch.TurnOutcome outcome, SemanticDuration duration,
                             StoryPatch.TimeResolution timeResolution, String interruptionReason,
                             CoreStateIntent coreState, List<EntityIntent> entities,
                             List<WorldFactIntent> worldFacts, List<StoryPatch.MemoryDraft> memories,
                             StoryPatch.DecisionCheckpoint decisionCheckpoint, List<String> sceneBeats) {
        this(outcome, duration, timeResolution, interruptionReason, coreState, null, entities,
                worldFacts, memories, decisionCheckpoint, sceneBeats);
    }

    public StoryTurnDecision {
        interruptionReason = normalized(interruptionReason);
        entities = safe(entities);
        worldFacts = safe(worldFacts);
        memories = safe(memories);
        decisionCheckpoint = decisionCheckpoint == null
                ? new StoryPatch.DecisionCheckpoint(false, null) : decisionCheckpoint;
        sceneBeats = safe(sceneBeats).stream().map(StoryTurnDecision::normalized)
                .filter(value -> !value.isBlank()).toList();
    }

    /** 主角核心投影的可选最终变化；人生目标只能通过专用修正流程改变。 */
    public record CoreStateIntent(String location, String condition, String realm) {}

    /** 模型对行动的简短承接摘要；不是独立授权证明，也不是已验算资产事实或内部推理过程。 */
    public record ActionUnderstanding(String purpose, List<String> constraints, List<ActionStep> steps) {
        public ActionUnderstanding {
            purpose = normalized(purpose);
            constraints = constraints == null ? null : List.copyOf(constraints);
            steps = safe(steps);
        }
    }

    public record ActionStep(String action, String result) {}

    /**
     * 主角本轮修行的最小语义意图。模型只能判断是否形成一枚进展、是否为玩家明确发起的突破尝试，
     * 不能指定目标境界、小阶段或进度数值。
     */
    public record CultivationIntent(boolean progressGained, boolean breakthroughAttempted,
                                    String reason, List<String> evidenceRefs) {
        public CultivationIntent {
            reason = normalized(reason);
            evidenceRefs = safe(evidenceRefs).stream().map(StoryTurnDecision::normalized)
                    .filter(value -> !value.isBlank()).distinct().toList();
        }
    }

    /**
     * 对一个长期实体的语义变化。existingEntityId 非空表示已有实体，否则建立新实体；localRef 只在本轮内使用。
     */
    public record EntityIntent(
            String existingEntityId,
            String localRef,
            String entityType,
            String name,
            String status,
            String summary,
            JsonNode attributes,
            StoryPatch.QuantityValue quantity,
            CustodyIntent custody,
            StoryPatch.AbilityState abilityState,
            StoryPatch.LifecycleValue lifecycle,
            StoryPatch.RelationshipValue relationship,
            Boolean visible
    ) {
        public EntityIntent {
            existingEntityId = normalized(existingEntityId);
            localRef = normalized(localRef);
            entityType = normalized(entityType);
            name = normalized(name);
            status = normalized(status);
            summary = normalized(summary);
        }
    }

    /** 物品最终保管意图；holderRef 是已有稳定引用，localHolderRef 指向本轮新人物。 */
    public record CustodyIntent(
            StoryPatch.HolderType holderType,
            String holderRef,
            String localHolderRef
    ) {
        public CustodyIntent {
            holderRef = normalized(holderRef);
            localHolderRef = normalized(localHolderRef);
        }
    }

    /** factId 为空表示扩展新事实，非空表示世界内自然转变；玩家修订仍走专用修正入口。 */
    public record WorldFactIntent(String factId, String statement, String reason) {
        public WorldFactIntent {
            factId = normalized(factId);
            statement = normalized(statement);
            reason = normalized(reason);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip();
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}

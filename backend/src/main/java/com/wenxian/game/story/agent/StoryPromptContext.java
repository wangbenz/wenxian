package com.wenxian.game.story.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryProjection.CoreState;

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
        ActionContext action
) {
    public record SystemContext(List<String> invariants, List<String> engineRules) {
        public SystemContext {
            invariants = safe(invariants);
            engineRules = safe(engineRules);
        }
    }

    public record ObjectiveContext(
            long worldTimeMinutes,
            CoreState protagonist,
            List<WorldFactView> worldFacts,
            List<EntityIndexEntry> entityIndex,
            List<EntityDetail> relatedEntities,
            PendingDecision pendingDecision
    ) {
        public ObjectiveContext(long worldTimeMinutes, CoreState protagonist,
                                List<WorldFactView> worldFacts, List<EntityIndexEntry> entityIndex,
                                List<EntityDetail> relatedEntities) {
            this(worldTimeMinutes, protagonist, worldFacts, entityIndex, relatedEntities, null);
        }

        public ObjectiveContext {
            worldFacts = safe(worldFacts);
            entityIndex = safe(entityIndex);
            relatedEntities = safe(relatedEntities);
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

    /** 摘要和上一回合只是叙事材料，不是权威状态来源。 */
    public record NarrativeContext(String rollingSummary, PreviousTurn previousTurn) {}

    /** 玩家行动作为数据单独传递，不能覆盖 SystemContext。 */
    public record ActionContext(String playerAction) {}

    public record WorldFactView(String id, String statement, int revision) {}

    /** 当前剧情已经停下并等待玩家亲自回答的决定；为空表示没有待确认事项。 */
    public record PendingDecision(String prompt) {}

    public record EntityIndexEntry(
            String id,
            String entityType,
            String name,
            String status,
            boolean visible,
            QuantityValue quantity,
            Boolean inPossession,
            AbilityState abilityState,
            LifecycleState lifecycle
    ) {}

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
            AbilityState abilityState,
            LifecycleState lifecycle
    ) {}

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
            String acceptedPatchSummary
    ) {}

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}

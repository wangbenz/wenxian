package com.wenxian.game.story.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.RelationshipValue;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryActionContract;
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
            WorldClock worldClock,
            CoreState protagonist,
            List<WorldFactView> worldFacts,
            List<EntityIndexEntry> entityIndex,
            List<EntityDetail> relatedEntities,
            InventoryView currentInventory,
            PendingDecision pendingDecision
    ) {
        public ObjectiveContext(long worldTimeMinutes, CoreState protagonist,
                                List<WorldFactView> worldFacts, List<EntityIndexEntry> entityIndex,
                                List<EntityDetail> relatedEntities) {
            this(worldTimeMinutes, WorldClock.from(worldTimeMinutes), protagonist, worldFacts, entityIndex, relatedEntities,
                    new InventoryView(true, List.of(), List.of()), null);
        }

        public ObjectiveContext(long worldTimeMinutes, CoreState protagonist,
                                List<WorldFactView> worldFacts, List<EntityIndexEntry> entityIndex,
                                List<EntityDetail> relatedEntities, PendingDecision pendingDecision) {
            this(worldTimeMinutes, WorldClock.from(worldTimeMinutes), protagonist, worldFacts, entityIndex, relatedEntities,
                    new InventoryView(true, List.of(), List.of()), pendingDecision);
        }

        public ObjectiveContext {
            worldClock = worldClock == null ? WorldClock.from(worldTimeMinutes) : worldClock;
            worldFacts = safe(worldFacts);
            entityIndex = safe(entityIndex);
            relatedEntities = safe(relatedEntities);
            currentInventory = currentInventory == null
                    ? new InventoryView(true, List.of(), List.of()) : currentInventory;
        }
    }

    /**
     * 供模型理解的可读世界时钟。总分钟仍是服务端计算依据；日序、时刻和昼夜阶段只用于避免正文把
     * 上午误写成深夜，不承载历法或剧情判断。
     */
    public record WorldClock(long dayNumber, int hour, int minute, String phase, String display) {
        public static WorldClock from(long worldTimeMinutes) {
            long safeMinutes = Math.max(0, worldTimeMinutes);
            long day = Math.floorDiv(safeMinutes, 1440L) + 1;
            int minuteOfDay = (int) Math.floorMod(safeMinutes, 1440L);
            int hour = minuteOfDay / 60;
            int minute = minuteOfDay % 60;
            String phase = switch (hour) {
                case 0, 1, 2, 3, 4 -> "深夜";
                case 5, 6 -> "清晨";
                case 7, 8, 9, 10 -> "上午";
                case 11, 12 -> "正午";
                case 13, 14, 15, 16 -> "下午";
                case 17, 18 -> "傍晚";
                default -> "夜晚";
            };
            String display = "第" + day + "日 " + String.format("%02d:%02d", hour, minute) + "（" + phase + "）";
            return new WorldClock(day, hour, minute, phase, display);
        }
    }

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

    /** 摘要和上一回合只是叙事材料，不是权威状态来源。 */
    public record NarrativeContext(String rollingSummary, PreviousTurn previousTurn) {}

    /**
     * 玩家行动作为数据单独传递，不能覆盖 SystemContext。服务端能可靠解析时，同时给出授权时长和
     * 预计结束时刻；模型必须叙述完整时间跨度，不能只写终点画面后让正文与日历脱节。
     */
    public record ActionContext(String playerAction, Long explicitDurationMinutes, WorldClock expectedEndClock,
                                StoryActionContract factContract) {
        public ActionContext(String playerAction) {
            this(playerAction, null, null, null);
        }

        public ActionContext(String playerAction, Long explicitDurationMinutes, WorldClock expectedEndClock) {
            this(playerAction, explicitDurationMinutes, expectedEndClock, null);
        }
    }

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
            CustodyValue custody,
            AbilityState abilityState,
            LifecycleState lifecycle,
            RelationshipValue relationship
    ) {
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

    /** 返回一份仅增加已校验行动契约的新上下文，原客观事实与历史分区保持不变。 */
    public StoryPromptContext withActionContract(StoryActionContract contract) {
        ActionContext current = action;
        return new StoryPromptContext(system, objective, cognition, memory, narrative,
                new ActionContext(current.playerAction(), current.explicitDurationMinutes(),
                        current.expectedEndClock(), contract));
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
            String acceptedPatchSummary
    ) {}

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}

package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;

/**
 * 从剧情结果中编译出的长期可信事件。
 *
 * <p>事件只表达会影响后续回合的重大变化。抬手、查看伤口、短暂思考等叙事动作不会形成事件；
 * 数据库仍保存当前投影，事件清单在 V0.15 首阶段随回合结构化提案保存，不新增事件表。</p>
 */
public sealed interface TrustedStoryEvent permits TrustedStoryEvent.ActionResolved,
        TrustedStoryEvent.ItemChanged, TrustedStoryEvent.CharacterChanged,
        TrustedStoryEvent.AbilityChanged, TrustedStoryEvent.LocationChanged,
        TrustedStoryEvent.LandmarkEstablished, TrustedStoryEvent.TimeElapsed,
        TrustedStoryEvent.WorldFactChanged {

    EventType type();

    record ActionResolved(String summary, Resolution resolution) implements TrustedStoryEvent {
        @Override public EventType type() { return EventType.ACTION_RESOLVED; }
    }

    record ItemChanged(EventType type, String entityId, String referenceKey, String name,
                       QuantityValue quantity, CustodyValue custody) implements TrustedStoryEvent {
        public ItemChanged {
            if (type != EventType.ITEM_ACQUIRED && type != EventType.ITEM_LOST
                    && type != EventType.ITEM_CONSUMED && type != EventType.ITEM_TRANSFERRED) {
                throw new IllegalArgumentException("物品事件类型不正确：" + type);
            }
        }
    }

    record CharacterChanged(EventType type, String entityId, String referenceKey, String name,
                            LifecycleState lifecycle, String relationshipLabel,
                            String reason) implements TrustedStoryEvent {
        public CharacterChanged {
            if (type != EventType.CHARACTER_ESTABLISHED && type != EventType.CHARACTER_STATE_CHANGED
                    && type != EventType.RELATION_CHANGED) {
                throw new IllegalArgumentException("人物事件类型不正确：" + type);
            }
        }
    }

    record AbilityChanged(EventType type, String entityId, String referenceKey, String name,
                          AbilityState state) implements TrustedStoryEvent {
        public AbilityChanged {
            if (type != EventType.ABILITY_KNOWN && type != EventType.ABILITY_LEARNING
                    && type != EventType.ABILITY_MASTERED && type != EventType.ABILITY_STATE_CHANGED) {
                throw new IllegalArgumentException("能力事件类型不正确：" + type);
            }
        }
    }

    record LocationChanged(String location) implements TrustedStoryEvent {
        @Override public EventType type() { return EventType.LOCATION_CHANGED; }
    }

    record LandmarkEstablished(String entityId, String referenceKey, String name) implements TrustedStoryEvent {
        @Override public EventType type() { return EventType.LANDMARK_ESTABLISHED; }
    }

    record TimeElapsed(SemanticDuration planned, SemanticDuration actual,
                       Completion completion, String interruptionReason) implements TrustedStoryEvent {
        @Override public EventType type() { return EventType.TIME_ELAPSED; }
    }

    record WorldFactChanged(EventType type, String factId, String statement,
                            String reason) implements TrustedStoryEvent {
        public WorldFactChanged {
            if (type != EventType.WORLD_FACT_ESTABLISHED && type != EventType.WORLD_FACT_REVISED) {
                throw new IllegalArgumentException("世界事实事件类型不正确：" + type);
            }
        }
    }

    enum EventType {
        ACTION_RESOLVED,
        ITEM_ACQUIRED,
        ITEM_LOST,
        ITEM_CONSUMED,
        ITEM_TRANSFERRED,
        CHARACTER_ESTABLISHED,
        CHARACTER_STATE_CHANGED,
        RELATION_CHANGED,
        ABILITY_KNOWN,
        ABILITY_LEARNING,
        ABILITY_MASTERED,
        ABILITY_STATE_CHANGED,
        LOCATION_CHANGED,
        LANDMARK_ESTABLISHED,
        TIME_ELAPSED,
        WORLD_FACT_ESTABLISHED,
        WORLD_FACT_REVISED
    }

    enum Resolution { RESOLVED, SUCCEEDED, FAILED, PARTIAL, INTERRUPTED }

    enum Completion { COMPLETED, INTERRUPTED }
}

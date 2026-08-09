package com.wenxian.game.story.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.WorldFactOperation;

import java.util.List;
import java.util.Map;

/**
 * 已提交回合与修正共同形成的当前事实投影。
 *
 * <p>投影用于本轮归并和查询，不代替不可变回合历史。实体和世界事实均以稳定 ID 作为身份，
 * 名称只用于展示，不能参与更新目标选择。</p>
 */
public record StoryProjection(
        long worldTimeMinutes,
        CoreState coreState,
        Map<String, EntityState> entities,
        Map<String, WorldFactState> worldFacts,
        Map<String, KnowledgeState> knowledge,
        List<MemoryState> memories
) {
    public StoryProjection {
        if (worldTimeMinutes < 0) throw new IllegalArgumentException("世界时间不能为负数");
        if (coreState == null) throw new IllegalArgumentException("核心状态不能为空");
        entities = entities == null ? Map.of() : Map.copyOf(entities);
        worldFacts = worldFacts == null ? Map.of() : Map.copyOf(worldFacts);
        knowledge = knowledge == null ? Map.of() : Map.copyOf(knowledge);
        memories = memories == null ? List.of() : List.copyOf(memories);
    }

    public record CoreState(String location, String condition, String realm, String lifeGoal) {}

    public record EntityState(
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

    public record WorldFactState(
            String id,
            String statement,
            int revision,
            boolean active,
            WorldFactOperation lastOperation,
            String lastReason
    ) {}

    public record KnowledgeState(
            String subjectRef,
            String topicRef,
            String summary,
            List<String> evidenceRefs
    ) {
        public KnowledgeState {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    public record MemoryState(
            String id,
            String memoryType,
            String title,
            String summary,
            String importance,
            List<String> evidenceRefs
    ) {
        public MemoryState {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }
}

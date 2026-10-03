package com.wenxian.game.story.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.RelationshipValue;
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
        StoryWorldTime worldTime,
        CoreState coreState,
        Map<String, EntityState> entities,
        Map<String, WorldFactState> worldFacts,
        Map<String, KnowledgeState> knowledge,
        List<MemoryState> memories,
        CreationContract creationContract,
        PlayPreference playPreference,
        CultivationSystem cultivationSystem,
        ProtagonistCultivation protagonistCultivation
) {
    public StoryProjection {
        if (worldTime == null) throw new IllegalArgumentException("世界时间不能为空");
        if (coreState == null) throw new IllegalArgumentException("核心状态不能为空");
        entities = entities == null ? Map.of() : Map.copyOf(entities);
        worldFacts = worldFacts == null ? Map.of() : Map.copyOf(worldFacts);
        knowledge = knowledge == null ? Map.of() : Map.copyOf(knowledge);
        memories = memories == null ? List.of() : List.copyOf(memories);
        if (creationContract == null) throw new IllegalArgumentException("命卷缺少创角契约");
        if (playPreference == null) throw new IllegalArgumentException("命卷缺少游玩偏好");
        if ((cultivationSystem == null) != (protagonistCultivation == null)) {
            throw new IllegalArgumentException("命卷修行体系与角色当前阶段必须同时存在");
        }
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
            CustodyValue custody,
            AbilityState abilityState,
            LifecycleState lifecycle,
            RelationshipValue relationship
    ) {
        /** 兼容旧投影构造；持有者会在持久化映射或 Reducer 中由 inPossession 补齐。 */
        public EntityState(String id, String entityType, String name, String status, String summary,
                           JsonNode attributes, boolean visible, QuantityValue quantity,
                           Boolean inPossession, AbilityState abilityState, LifecycleState lifecycle) {
            this(id, entityType, name, status, summary, attributes, visible, quantity,
                    inPossession, null, abilityState, lifecycle, null);
        }

        /** 兼容已包含物品保管关系、但尚未包含可信人物关系的投影构造。 */
        public EntityState(String id, String entityType, String name, String status, String summary,
                           JsonNode attributes, boolean visible, QuantityValue quantity,
                           Boolean inPossession, CustodyValue custody, AbilityState abilityState,
                           LifecycleState lifecycle) {
            this(id, entityType, name, status, summary, attributes, visible, quantity,
                    inPossession, custody, abilityState, lifecycle, null);
        }
    }

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

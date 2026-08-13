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
        long worldTimeMinutes,
        CoreState coreState,
        Map<String, EntityState> entities,
        Map<String, WorldFactState> worldFacts,
        Map<String, KnowledgeState> knowledge,
        List<MemoryState> memories,
        InitialContract initialContract
) {
    /** 兼容尚未提供角色根源契约的测试和旧调用方。 */
    public StoryProjection(long worldTimeMinutes, CoreState coreState,
                           Map<String, EntityState> entities,
                           Map<String, WorldFactState> worldFacts,
                           Map<String, KnowledgeState> knowledge,
                           List<MemoryState> memories) {
        this(worldTimeMinutes, coreState, entities, worldFacts, knowledge, memories, InitialContract.empty());
    }

    public StoryProjection {
        if (worldTimeMinutes < 0) throw new IllegalArgumentException("世界时间不能为负数");
        if (coreState == null) throw new IllegalArgumentException("核心状态不能为空");
        entities = entities == null ? Map.of() : Map.copyOf(entities);
        worldFacts = worldFacts == null ? Map.of() : Map.copyOf(worldFacts);
        knowledge = knowledge == null ? Map.of() : Map.copyOf(knowledge);
        memories = memories == null ? List.of() : List.copyOf(memories);
        initialContract = initialContract == null ? InitialContract.empty() : initialContract;
    }

    public record CoreState(String location, String condition, String realm, String lifeGoal) {}

    /**
     * 创建命卷时由玩家确认的角色根源与世界起点。
     *
     * <p>这些内容是后续模型可引用的客观来源，但不把根源文本中的每个名词立即升级为永久实体。
     * 当人物、物品或地点在后续行动中产生长期变化时，再由 StoryPatch 晋升为稳定实体。</p>
     */
    public record InitialContract(
            String protagonistName,
            String race,
            String gender,
            String origin,
            String startingRealm,
            String storyPreference,
            String worldCode,
            String worldName,
            String worldPremise,
            String cultivationStyle,
            String openingRegion,
            String openingNarrative
    ) {
        public InitialContract {
            protagonistName = text(protagonistName);
            race = text(race);
            gender = text(gender);
            origin = text(origin);
            startingRealm = text(startingRealm);
            storyPreference = text(storyPreference);
            worldCode = text(worldCode);
            worldName = text(worldName);
            worldPremise = text(worldPremise);
            cultivationStyle = text(cultivationStyle);
            openingRegion = text(openingRegion);
            openingNarrative = text(openingNarrative);
        }

        /** 兼容尚未把开局境界纳入根源契约的旧调用方。 */
        public InitialContract(String protagonistName, String race, String gender, String origin,
                               String storyPreference, String worldCode, String worldName,
                               String worldPremise, String cultivationStyle, String openingRegion,
                               String openingNarrative) {
            this(protagonistName, race, gender, origin, "", storyPreference, worldCode, worldName,
                    worldPremise, cultivationStyle, openingRegion, openingNarrative);
        }

        public static InitialContract empty() {
            return new InitialContract("", "", "", "", "", "", "", "", "", "", "", "");
        }

        private static String text(String value) {
            return value == null ? "" : value.strip();
        }
    }

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

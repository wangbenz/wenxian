package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.DecisionCheckpoint;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.WorldFactOperation;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.domain.StoryProjection.KnowledgeState;
import com.wenxian.game.story.domain.StoryProjection.WorldFactState;
import com.wenxian.game.story.persistence.StoryMemoryRepository.StoredMemory;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoryProjectionPersistenceMapperTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final StoryProjectionPersistenceMapper mapper = new StoryProjectionPersistenceMapper(json);

    @Test
    void roundTripsNeutralProjectionWithoutReplacingExistingStoryState() {
        Instant now = Instant.parse("2026-08-09T09:00:00Z");
        ObjectNode state = baseState();
        StoryProjection projection = new StoryProjection(525_720,
                new StoryProjection.CoreState("青岐城北门", "轻伤", "筑基初期", "查明父亲失踪真相"),
                Map.of(
                        "item-1", new EntityState("item-1", "ITEM", "青纹令", "收进行囊", "旧识所赠",
                                json.createObjectNode().put("source", "旧识"), false,
                                new QuantityValue(true, 3), true, null, null),
                        "ability-1", new EntityState("ability-1", "ABILITY", "听风诀", "可以施展", "辨别远声",
                                json.createObjectNode(), true, null, null, AbilityState.MASTERED, null),
                        "character-1", new EntityState("character-1", "CHARACTER", "韩药师", "已经身亡", "死于旧案",
                                json.createObjectNode(), false, null, null, null, LifecycleState.DEAD)),
                Map.of("fact-1", new WorldFactState("fact-1", "天门已经关闭", 2, true,
                        WorldFactOperation.REVISE, "玩家确认修订")),
                Map.of("character-1\u0000fact-1", new KnowledgeState("character-1", "fact-1",
                        "韩药师生前并不知道真相", List.of("turn-8"))),
                List.of());

        ObjectNode writtenState = mapper.writeState(state, projection);
        List<Entity> writtenEntities = mapper.writeEntities("story-save-1", projection, List.of(), 9, now);
        StoryProjection loaded = mapper.load(save(writtenState), writtenEntities,
                List.of(new StoredMemory("memory-1", "PROMISE", "三年之约", "三年后回山门", 4, 200, "KEY")));

        assertEquals("玄墟界", writtenState.path("world").path("name").asText(), "旧世界展示字段必须保留");
        assertEquals("上一回正文", writtenState.path("narrative").asText(), "正文不能被当前投影覆盖");
        assertEquals(projection.coreState(), loaded.coreState());
        assertEquals(projection.worldTimeMinutes(), loaded.worldTimeMinutes());
        assertEquals(3, loaded.entities().get("item-1").quantity().value());
        assertFalse(loaded.entities().get("item-1").visible(), "隐藏实体必须保留在权威投影");
        assertEquals(AbilityState.MASTERED, loaded.entities().get("ability-1").abilityState());
        assertFalse(loaded.entities().get("ability-1").attributes()
                .has(StoryProjectionPersistenceMapper.TRUSTED_ENTITY), "内部可信字段不能进入普通属性上下文");
        assertEquals(LifecycleState.DEAD, loaded.entities().get("character-1").lifecycle());
        assertEquals("天门已经关闭", loaded.worldFacts().get("fact-1").statement());
        assertEquals("韩药师生前并不知道真相",
                loaded.knowledge().get("character-1\u0000fact-1").summary());
        assertEquals("PROMISE", loaded.memories().getFirst().memoryType());
    }

    @Test
    void legacyQuantityIsExplicitButMissingQuantityStaysUnknown() {
        ObjectNode legacy = json.createObjectNode().put("quantity", 2).put("inPossession", true);
        ObjectNode unknown = json.createObjectNode().put("inPossession", true);
        StoryProjection loaded = mapper.load(save(baseState()), List.of(
                entity("item-known", legacy), entity("item-unknown", unknown)), List.of());

        assertEquals(new QuantityValue(true, 2), loaded.entities().get("item-known").quantity());
        assertNull(loaded.entities().get("item-unknown").quantity(),
                "缺少数量时不能沿用旧逻辑伪造为1");
    }

    @Test
    void rejectsCorruptedTrustedEnumInsteadOfGuessingFromStatusText() {
        ObjectNode attributes = json.createObjectNode();
        attributes.putObject(StoryProjectionPersistenceMapper.TRUSTED_ENTITY)
                .put("lifecycle", "已经死了大概吧");

        assertThrows(IllegalStateException.class,
                () -> mapper.load(save(baseState()), List.of(entity("character-broken", attributes)), List.of()));
    }

    @Test
    void rejectsCorruptedTrustedQuantityInsteadOfInventingInventory() {
        ObjectNode attributes = json.createObjectNode();
        attributes.putObject(StoryProjectionPersistenceMapper.TRUSTED_ENTITY)
                .putObject("quantity").put("known", false).put("value", 2);

        assertThrows(IllegalStateException.class,
                () -> mapper.load(save(baseState()), List.of(entity("item-broken", attributes)), List.of()));
    }

    @Test
    void roundTripsAndClearsPendingMajorDecision() {
        ObjectNode state = baseState();

        mapper.writeDecisionCheckpoint(state,
                new DecisionCheckpoint(true, "是否接受使者提出的入门条件？"));
        assertEquals("是否接受使者提出的入门条件？",
                mapper.loadDecisionCheckpoint(state).prompt());

        mapper.writeDecisionCheckpoint(state, new DecisionCheckpoint(false, null));
        assertFalse(state.has(StoryProjectionPersistenceMapper.TRUSTED_SCENE));
        assertNull(mapper.loadDecisionCheckpoint(state));
    }

    private ObjectNode baseState() {
        ObjectNode state = json.createObjectNode();
        state.put("worldTimeMinutes", 120);
        state.put("currentLocation", "青岐城南门");
        state.put("condition", "平静");
        state.putObject("protagonist").put("name", "陆沉").put("realm", "引气").put("lifeGoal", "问道");
        state.putObject("world").put("name", "玄墟界");
        state.put("narrative", "上一回正文");
        return state;
    }

    private Entity entity(String id, ObjectNode attributes) {
        String type = id.startsWith("character") ? "CHARACTER" : "ITEM";
        return new Entity(id, "story-save-1", type, id, "现状", true, "摘要", attributes,
                1, 1, Instant.EPOCH, Instant.EPOCH);
    }

    private StoredSave save(ObjectNode state) {
        return new StoredSave("story-save-1", "user-1", 1, "ACTIVE", "XUANXU",
                "LIGHT_NARRATIVE", 1, 1, "NATIVE", "V0.14", null, null, state,
                1, 1, "V0.14", "V0.14", Instant.EPOCH, Instant.EPOCH);
    }
}

package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.DecisionCheckpoint;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.RelationshipValue;
import com.wenxian.game.story.domain.StoryPatch.WorldFactOperation;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.domain.StoryProjection.InitialContract;
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
    void legacyCharacterLifecycleBecomesUnknownWithoutReadingStatusKeywords() {
        ObjectNode attributes = json.createObjectNode().put("relationship", "旧识");
        StoryProjection loaded = mapper.load(save(baseState()), List.of(
                new Entity("character-legacy", "story-save-1", "CHARACTER", "旧识", "仍在村中",
                        true, "没有结构化生死记录", attributes, 1, 1,
                        Instant.EPOCH, Instant.EPOCH)), List.of());

        assertEquals(LifecycleState.UNKNOWN, loaded.entities().get("character-legacy").lifecycle());
        assertEquals("旧识", loaded.entities().get("character-legacy").relationship().label());
    }

    @Test
    void hiddenLegacyItemCannotRemainPossessedInTrustedProjection() {
        ObjectNode attributes = json.createObjectNode().put("quantity", 1).put("inPossession", true);
        Entity hidden = new Entity("item-hidden", "story-save-1", "ITEM", "旧干粮", "已移出行囊",
                false, "只保留历史记录", attributes, 1, 2, Instant.EPOCH, Instant.EPOCH);

        StoryProjection loaded = mapper.load(save(baseState()), List.of(hidden), List.of());

        assertFalse(loaded.entities().get("item-hidden").inPossession());
    }

    @Test
    void roundTripsCharacterCustodyWithoutAddingDatabaseColumns() {
        EntityState gifted = new EntityState("item-gifted", "ITEM", "断剑尖", "交给老妇保管", "赠物",
                json.createObjectNode(), true, new QuantityValue(true, 1), false,
                new CustodyValue(HolderType.CHARACTER, "character-old-woman"), null, null);
        StoryProjection projection = new StoryProjection(120,
                new StoryProjection.CoreState("村口", "平静", "凡俗", "问道"),
                Map.of(gifted.id(), gifted), Map.of(), Map.of(), List.of());

        List<Entity> stored = mapper.writeEntities("story-save-1", projection, List.of(), 2, Instant.EPOCH);
        StoryProjection loaded = mapper.load(save(baseState()), stored, List.of());

        assertFalse(loaded.entities().get(gifted.id()).inPossession());
        assertEquals(HolderType.CHARACTER, loaded.entities().get(gifted.id()).custody().holderType());
        assertEquals("character-old-woman", loaded.entities().get(gifted.id()).custody().holderRef());
    }

    @Test
    void roundTripsTrustedRelationshipWithoutAddingDatabaseColumns() {
        EntityState character = new EntityState("character-friend", "CHARACTER", "阿禾", "同行", "共同脱险",
                json.createObjectNode(), true, null, null, null, null, LifecycleState.ACTIVE,
                new RelationshipValue("生死之交", "共同逃出矿洞"));
        StoryProjection projection = new StoryProjection(120,
                new StoryProjection.CoreState("村口", "平静", "凡俗", "问道"),
                Map.of(character.id(), character), Map.of(), Map.of(), List.of());

        List<Entity> stored = mapper.writeEntities("story-save-1", projection, List.of(), 2, Instant.EPOCH);
        StoryProjection loaded = mapper.load(save(baseState()), stored, List.of());

        assertEquals("生死之交", loaded.entities().get(character.id()).relationship().label());
        assertEquals("共同逃出矿洞", loaded.entities().get(character.id()).relationship().changeReason());
        assertFalse(loaded.entities().get(character.id()).attributes()
                .has(StoryProjectionPersistenceMapper.TRUSTED_ENTITY));
    }

    @Test
    void incrementalCharacterUpdateCannotEraseConfirmedProfileAttributes() {
        ObjectNode existingAttributes = json.createObjectNode()
                .put("tier", "KNOWN")
                .put("identity", "石溪村村民")
                .put("relationship", "旧识")
                .put("lastKnownLocation", "石溪村村尾");
        Entity existing = new Entity("character-old-woman", "story-save-1", "CHARACTER", "老妇人",
                "居住村尾", true, "曾收留主角", existingAttributes,
                1, 1, Instant.EPOCH, Instant.EPOCH);
        EntityState changedRelationship = new EntityState(existing.id(), "CHARACTER", "老妇人",
                "已应允结交", "曾收留主角", json.createObjectNode().put("relationship", "忘年之交"),
                true, null, false, new CustodyValue(HolderType.UNKNOWN, null), AbilityState.UNKNOWN,
                LifecycleState.UNKNOWN, new RelationshipValue("忘年之交", "双方明确应允"));
        StoryProjection projection = new StoryProjection(120,
                new StoryProjection.CoreState("村尾", "平静", "凡俗", "问道"),
                Map.of(changedRelationship.id(), changedRelationship), Map.of(), Map.of(), List.of());

        Entity stored = mapper.writeEntities("story-save-1", projection, List.of(existing), 2, Instant.EPOCH)
                .getFirst();

        assertEquals("石溪村村民", stored.attributes().path("identity").asText());
        assertEquals("石溪村村尾", stored.attributes().path("lastKnownLocation").asText());
        assertEquals("忘年之交", stored.attributes().path("relationship").asText());
        assertFalse(stored.attributes().has("inPossession"), "人物不应保存物品持有字段");
        assertNull(stored.attributes().path(StoryProjectionPersistenceMapper.TRUSTED_ENTITY)
                .get("abilityState"), "人物不应保存能力掌握字段");
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

    @Test
    void preservesInitialContractAcrossLaterProjectionWrites() {
        InitialContract initial = new InitialContract("柳青", "灵族", "女", "矿石成灵，随身带着醒尘剑",
                "凝魄境", "爽快探索", "XUANXU", "玄墟界", "诸道并存", "修行不拘一途", "青岐州",
                "你在矿洞深处睁开眼睛。柳青儿正在洞口等你。 ");
        ObjectNode created = mapper.writeInitialContract(baseState(), initial);
        StoryProjection loaded = mapper.load(save(created), List.of(), List.of());
        ObjectNode afterTurn = mapper.writeState(created, loaded);
        StoryProjection reloaded = mapper.load(save(afterTurn), List.of(), List.of());

        assertEquals(initial.origin(), reloaded.initialContract().origin());
        assertEquals(initial.openingNarrative().strip(), reloaded.initialContract().openingNarrative());
        assertEquals("柳青", reloaded.initialContract().protagonistName());
        assertEquals("凝魄境", reloaded.initialContract().startingRealm());
    }

    @Test
    void backfillsLegacyInitialContractOnceWithoutPretendingCurrentRealmWasStartingRealm() {
        ObjectNode legacy = baseState();
        legacy.put("turnNumber", 37);
        legacy.withObject("protagonist")
                .put("race", "妖族")
                .put("gender", "女")
                .put("origin", "山中白狐得月华化形")
                .put("realm", "金丹初期");

        StoryProjection loaded = mapper.load(save(legacy), List.of(), List.of());
        ObjectNode firstTrustedWrite = mapper.writeState(legacy, loaded);
        StoryProjection reloaded = mapper.load(save(firstTrustedWrite), List.of(), List.of());

        assertEquals("山中白狐得月华化形", reloaded.initialContract().origin());
        assertEquals("历史命卷未记录", reloaded.initialContract().startingRealm());
        assertEquals("陆沉", reloaded.initialContract().protagonistName());

        firstTrustedWrite.withObject("protagonist").put("origin", "错误覆盖").put("realm", "化神");
        StoryProjection changedCurrentState = mapper.load(save(firstTrustedWrite), List.of(), List.of());
        ObjectNode secondTrustedWrite = mapper.writeState(firstTrustedWrite, changedCurrentState);
        StoryProjection finalProjection = mapper.load(save(secondTrustedWrite), List.of(), List.of());

        assertEquals("山中白狐得月华化形", finalProjection.initialContract().origin(),
                "初始契约回填后不能再随当前状态变化");
        assertEquals("历史命卷未记录", finalProjection.initialContract().startingRealm());
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
        return new StoredSave("story-save-1", "user-1", 1, 1, "ACTIVE", "XUANXU",
                "LIGHT_NARRATIVE", 1, 1, "NATIVE", "V0.14", null, null, state,
                1, 1, "V0.14", "V0.14", Instant.EPOCH, Instant.EPOCH, null, null, null);
    }
}

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
import com.wenxian.game.story.domain.CultivationSystem;
import com.wenxian.game.story.domain.ProtagonistCultivation;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryWorldTime;
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
        StoryProjection projection = new StoryProjection(new StoryWorldTime(2, 6, StoryWorldTime.Phase.DUSK),
                new StoryProjection.CoreState("青岐城北门", "轻伤", "筑基初期", "查明父亲失踪真相"),
                Map.of(
                        "item-1", new EntityState("item-1", "ITEM", "青纹令", "收进行囊", "旧识所赠",
                                json.createObjectNode().put("source", "旧识"), false,
                                new QuantityValue(true, 3), true,
                                new CustodyValue(HolderType.PLAYER, null), null, null),
                        "ability-1", new EntityState("ability-1", "ABILITY", "听风诀", "可以施展", "辨别远声",
                                json.createObjectNode(), true, null, null, AbilityState.MASTERED, null),
                        "character-1", new EntityState("character-1", "CHARACTER", "韩药师", "已经身亡", "死于旧案",
                                json.createObjectNode(), false, null, null, null, LifecycleState.DEAD)),
                Map.of("fact-1", new WorldFactState("fact-1", "天门已经关闭", 2, true,
                        WorldFactOperation.REVISE, "玩家确认修订")),
                Map.of("character-1\u0000fact-1", new KnowledgeState("character-1", "fact-1",
                        "韩药师生前并不知道真相", List.of("turn-8"))),
                List.of(), com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(), null, null);

        ObjectNode writtenState = mapper.writeState(state, projection);
        List<Entity> writtenEntities = mapper.writeEntities("story-save-1", projection, List.of(), 9, now);
        StoryProjection loaded = mapper.load(save(writtenState), writtenEntities,
                List.of(new StoredMemory("memory-1", "PROMISE", "三年之约", "三年后回山门", 4, 200, "KEY")));

        assertEquals("玄墟界", writtenState.path("trustedProjection").path("creationContract")
                .path("worldBinding").path("nameSnapshot").asText(), "创角世界绑定必须保留");
        assertEquals("上一回正文", writtenState.path("scene").path("narrative").asText(),
                "正文不能被当前投影覆盖");
        assertEquals(projection.coreState().location(), loaded.coreState().location());
        assertEquals(projection.coreState().condition(), loaded.coreState().condition());
        assertEquals("引气初期", loaded.coreState().realm(), "当前境界必须来自已确认修行阶段");
        assertEquals(projection.coreState().lifeGoal(), loaded.coreState().lifeGoal());
        assertEquals(projection.worldTime(), loaded.worldTime());
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
    void rejectsLegacyPublicAttributesInsteadOfLoadingThemAsTrustedFacts() {
        ObjectNode item = json.createObjectNode().put("quantity", 2).put("inPossession", true);
        ObjectNode character = json.createObjectNode().put("relationship", "旧识");

        assertThrows(IllegalStateException.class,
                () -> mapper.load(save(baseState()), List.of(entity("item-legacy", item)), List.of()));
        assertThrows(IllegalStateException.class,
                () -> mapper.load(save(baseState()), List.of(entity("character-legacy", character)), List.of()));
    }

    @Test
    void hiddenCurrentItemCannotRemainPossessedInTrustedProjection() {
        ObjectNode attributes = json.createObjectNode();
        ObjectNode trusted = attributes.putObject(StoryProjectionPersistenceMapper.TRUSTED_ENTITY);
        trusted.set("quantity", json.valueToTree(new QuantityValue(true, 1)));
        trusted.set("custody", json.valueToTree(new CustodyValue(HolderType.PLAYER, null)));
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
        StoryProjection projection = new StoryProjection(StoryWorldTime.opening(),
                new StoryProjection.CoreState("村口", "平静", "凡俗", "问道"),
                Map.of(gifted.id(), gifted), Map.of(), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(), null, null);

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
        StoryProjection projection = new StoryProjection(StoryWorldTime.opening(),
                new StoryProjection.CoreState("村口", "平静", "凡俗", "问道"),
                Map.of(character.id(), character), Map.of(), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(), null, null);

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
        StoryProjection projection = new StoryProjection(StoryWorldTime.opening(),
                new StoryProjection.CoreState("村尾", "平静", "凡俗", "问道"),
                Map.of(changedRelationship.id(), changedRelationship), Map.of(), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(), null, null);

        Entity stored = mapper.writeEntities("story-save-1", projection, List.of(existing), 2, Instant.EPOCH)
                .getFirst();

        assertEquals("石溪村村民", stored.attributes().path("identity").asText());
        assertEquals("石溪村村尾", stored.attributes().path("lastKnownLocation").asText());
        assertFalse(stored.attributes().has("relationship"), "可信人物关系不再复制到普通展示属性");
        assertEquals("忘年之交", stored.attributes().path(StoryProjectionPersistenceMapper.TRUSTED_ENTITY)
                .path("relationship").path("label").asText());
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
        assertEquals(true, state.path(StoryProjectionPersistenceMapper.TRUSTED_SCENE)
                .path("pendingDecision").isNull());
        assertNull(mapper.loadDecisionCheckpoint(state));
    }

    @Test
    void preservesCreationContractAcrossLaterProjectionWrites() {
        ObjectNode created = baseState();
        StoryProjection loaded = mapper.load(save(created), List.of(), List.of());
        ObjectNode afterTurn = mapper.writeState(created, loaded);
        StoryProjection reloaded = mapper.load(save(afterTurn), List.of(), List.of());

        assertEquals(loaded.creationContract(), reloaded.creationContract());
        assertEquals(loaded.playPreference(), reloaded.playPreference());
    }

    @Test
    void rejectsLegacyFlatStateInsteadOfBackfillingFacts() {
        ObjectNode legacy = baseState();
        legacy.remove("trustedProjection");
        legacy.remove("coreState");
        legacy.putObject("protagonist").put("name", "旧角色");
        assertThrows(IllegalStateException.class,
                () -> mapper.load(save(legacy), List.of(), List.of()));
    }

    @Test
    void roundTripsConfirmedCultivationAndProjectsDisplayRealmFromStableStage() {
        CultivationSystem system = cultivationSystem();
        ProtagonistCultivation cultivation = new ProtagonistCultivation(system.systemId(), "stage-02",
                "已经能够稳定引气入体");
        StoryProjection projection = new StoryProjection(StoryWorldTime.opening(),
                new StoryProjection.CoreState("青岐城南门", "平静", "不应成为权威的旧字符串", "问道"),
                Map.of(), Map.of(), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(), system, cultivation);

        ObjectNode written = mapper.writeState(baseState(), projection);
        StoryProjection loaded = mapper.load(save(written), List.of(), List.of());

        assertEquals(system, loaded.cultivationSystem());
        assertEquals(cultivation, loaded.protagonistCultivation());
        assertEquals("炼气初期", loaded.coreState().realm());
    }

    @Test
    void rejectsMissingCultivationInsteadOfInventingCurrentRealm() {
        ObjectNode legacy = baseState();
        legacy.path(StoryProjectionPersistenceMapper.TRUSTED_PROJECTION);
        ((ObjectNode) legacy.path(StoryProjectionPersistenceMapper.TRUSTED_PROJECTION))
                .remove(List.of("cultivationSystem", "protagonistCultivation"));
        assertThrows(IllegalStateException.class,
                () -> mapper.load(save(legacy), List.of(), List.of()));
    }

    @Test
    void rejectsHalfWrittenCultivationInsteadOfGuessingCurrentStage() {
        ObjectNode broken = baseState();
        ObjectNode trusted = broken.putObject(StoryProjectionPersistenceMapper.TRUSTED_PROJECTION);
        trusted.set("creationContract", json.valueToTree(
                com.wenxian.game.story.support.StoryTestFixtures.creationContract()));
        trusted.set("playPreference", json.valueToTree(
                com.wenxian.game.story.support.StoryTestFixtures.playPreference()));
        trusted.set("cultivationSystem", json.valueToTree(cultivationSystem()));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> mapper.load(save(broken), List.of(), List.of()));

        assertEquals("命卷修行体系与角色当前阶段必须同时存在", error.getMessage());
    }

    private ObjectNode baseState() {
        ObjectNode state = json.createObjectNode();
        state.put("schemaVersion", 1);
        state.put("saveId", "story-save-1");
        state.put("turnNumber", 1);
        state.put("status", "ACTIVE");
        state.set("worldTime", json.valueToTree(StoryWorldTime.opening()));
        state.putObject("coreState").put("currentLocationRef", "region:xuanxu:qingqi")
                .put("currentLocationName", "青岐城南门").put("condition", "平静").put("lifeGoal", "问道");
        state.putObject("scene").put("title", "旧场景").put("narrative", "上一回正文")
                .put("situation", "城门戒严").putNull("pendingDecision").putArray("choices");
        CultivationSystem system = cultivationSystem();
        return mapper.writeInitialCreation(state,
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(), system,
                new ProtagonistCultivation(system.systemId(), "stage-01", ""));
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

    private CultivationSystem cultivationSystem() {
        return new CultivationSystem("cultivation-test", 1, "青冥炼真道", "借天地清气磨炼真形。", "人族",
                "stage-01", List.of(
                stage("stage-01", 1, "引气", List.of("感气"), "stage-02"),
                stage("stage-02", 2, "炼气", List.of("练气"), "stage-03"),
                stage("stage-03", 3, "筑基", List.of("道基"), "stage-04"),
                stage("stage-04", 4, "金丹", List.of("结丹"), "stage-05"),
                stage("stage-05", 5, "元婴", List.of("婴成"), "stage-06"),
                stage("stage-06", 6, "化神", List.of("神变"), null)
        ), new CultivationSystem.Confirmation(CultivationSystem.ConfirmationSource.PLAYER_CONFIRMED_RECOMMENDATION,
                Instant.parse("2026-08-20T12:00:00Z")));
    }

    private CultivationSystem.Stage stage(String id, int order, String name, List<String> aliases,
                                          String nextId) {
        return new CultivationSystem.Stage(id, order, name, name + "阶段的世界内含义。", aliases,
                nextId == null ? List.of() : List.of(nextId));
    }
}

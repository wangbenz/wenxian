package com.wenxian.game.story.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.service.ActiveSavePolicyService;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.story.agent.StoryModelSelectionGateway;
import com.wenxian.game.story.application.CharacterCreationCatalogService;
import com.wenxian.game.story.application.WorldPackageCatalog;
import com.wenxian.game.story.application.StorySaveViewService;
import com.wenxian.game.story.application.StorySaveCreationService;
import com.wenxian.game.story.application.OpeningSceneService;
import com.wenxian.game.story.application.StoryTurnFailureMapper;
import com.wenxian.game.story.domain.CreationContract;
import com.wenxian.game.story.domain.CultivationSystem;
import com.wenxian.game.story.domain.PlayPreference;
import com.wenxian.game.story.domain.ProtagonistCultivation;
import com.wenxian.game.story.model.StoryApiModels.CreateSaveRequest;
import com.wenxian.game.story.model.StoryApiModels;
import com.wenxian.game.story.persistence.StoryConversationRepository;
import com.wenxian.game.story.persistence.StoryCorrectionRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewSave;
import com.wenxian.game.story.persistence.StorySaveRepository;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 普通回合重构后继续保护统一命卷的读取、整理和逻辑删除能力。 */
class PersistedLightNarrativeEngineLifecycleTest {
    private static final UserIdentity USER = new UserIdentity("lifecycle-user", "lifecycle@test.local", true);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void keepsNonTurnSaveLifecycleAvailableAfterRemovingLegacyTurnPipeline() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            saves.insert(new NewSave("story-lifecycle", USER.userId(), 1, 1, "ACTIVE", "XUANXU",
                    "LIGHT_NARRATIVE", 1, 2, "NATIVE", "V0.16", null, null,
                    state(), 3, "LOCAL_TEST", "LOCAL_TEST", Instant.now()));
            ObjectNode attributes = mapper.createObjectNode();
            attributes.put("itemType", "信物");
            ObjectNode trusted = attributes.putObject("_trusted");
            trusted.putObject("quantity").put("known", true).put("value", 1);
            trusted.putObject("custody").put("holderType", "PLAYER").putNull("holderRef");
            entities.insert(new Entity("item-token", "story-lifecycle", "ITEM", "青纹令", "随身携带",
                    true, "一枚青纹令", attributes, 1, 3, Instant.now(), Instant.now()));
            new StoryConversationRepository(jdbc).initialize("story-lifecycle", "你已经进入青岐城。", 3);
            PersistedLightNarrativeEngine engine = engine(database, saves, entities, memories);

            assertThat(engine.get(USER, "story-lifecycle").state().inventory()).hasSize(1);
            var removed = engine.removeEntity(USER, "story-lifecycle", "item-token", 1);
            assertThat(removed.state().inventory()).isEmpty();
            engine.deleteSave(USER, "story-lifecycle");
            assertThat(engine.list(USER)).isEmpty();
            assertThat(jdbc.queryForObject("select status from story_save where id='story-lifecycle'",
                    String.class)).isEqualTo("ARCHIVED");
        } finally {
            database.shutdown();
        }
    }

    @Test
    void genericRuntimeListsOnlyNeutralStorySaves() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            PersistedLightNarrativeEngine engine = engine(database,
                    new StorySaveRepository(jdbc, mapper), new StoryEntityRepository(jdbc, mapper),
                    new StoryMemoryRepository(jdbc));
            assertThat(engine.list(USER)).isEmpty();
        } finally {
            database.shutdown();
        }
    }

    @Test
    void incompatibleEngineSaveRemainsVisibleAndCanStillBeDeleted() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.update("""
                    insert into story_engine_catalog(
                        engine_code,engine_version,display_name,status,min_schema_version,current_schema_version,
                        new_save_enabled,continuation_enabled,created_at,updated_at
                    ) values ('LIGHT_NARRATIVE',9,'历史引擎','READ_ONLY',2,2,false,false,current_timestamp,current_timestamp)
                    """);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            saves.insert(new NewSave("story-old-engine", USER.userId(), 1, 1, "ACTIVE", "XUANXU",
                    "LIGHT_NARRATIVE", 9, 2, "NATIVE", "V0.15", null, null,
                    state(), 3, "LOCAL_TEST", "LOCAL_TEST", Instant.now()));
            PersistedLightNarrativeEngine engine = engine(database, saves,
                    new StoryEntityRepository(jdbc, mapper), new StoryMemoryRepository(jdbc));

            assertThat(engine.list(USER)).singleElement().satisfies(summary -> {
                assertThat(summary.saveId()).isEqualTo("story-old-engine");
                assertThat(summary.mode()).isEqualTo("MIGRATION_REQUIRED");
                assertThat(summary.engineVersion()).isEqualTo(9);
                assertThat(summary.capabilities()).containsExactly("DELETE");
            });
            assertThatThrownBy(() -> engine.get(USER, "story-old-engine"))
                    .isInstanceOf(GameApiException.class)
                    .extracting(error -> ((GameApiException) error).code())
                    .isEqualTo("STORY_ENGINE_MISMATCH");

            engine.deleteSave(USER, "story-old-engine");
            assertThat(engine.list(USER)).isEmpty();
        } finally {
            database.shutdown();
        }
    }

    @Test
    void brokenSaveStateCannotBlockAccountSaveListOrHealthySave() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            saves.insert(new NewSave("story-healthy", USER.userId(), 1, 1, "ACTIVE", "XUANXU",
                    "LIGHT_NARRATIVE", 1, 2, "NATIVE", "V0.16", null, null,
                    state(), 3, "LOCAL_TEST", "LOCAL_TEST", Instant.now()));
            saves.insert(new NewSave("story-broken", USER.userId(), 2, 2, "ACTIVE", "TAIHUA",
                    "LIGHT_NARRATIVE", 1, 2, "NATIVE", "V0.16", null, null,
                    mapper.createObjectNode(), 9, "LOCAL_TEST", "LOCAL_TEST", Instant.now()));
            jdbc.update("update story_save set state_json=? where id=?", "{broken-json", "story-broken");
            PersistedLightNarrativeEngine engine = engine(database, saves, entities, memories);

            assertThat(saves.hasActive(USER.userId())).isTrue();
            assertThat(engine.list(USER)).extracting("saveId")
                    .containsExactly("story-healthy", "story-broken");
            assertThat(engine.list(USER).get(1).playerName()).isEqualTo("异常命卷 2");
            assertThat(engine.list(USER).get(1).capabilities()).containsExactly("DELETE");
            assertThat(engine.get(USER, "story-healthy").state().protagonist().name()).isEqualTo("陆沉");
            assertThatThrownBy(() -> engine.get(USER, "story-broken"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("统一命卷 JSON 无法读取");
        } finally {
            database.shutdown();
        }
    }

    @Test
    void createsNeutralStorySaveWithoutCallingV014CreationServices() throws Exception {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            PersistedLightNarrativeEngine engine = engine(database,
                    new StorySaveRepository(jdbc, mapper), new StoryEntityRepository(jdbc, mapper),
                    new StoryMemoryRepository(jdbc));

            var bootstrap = engine.bootstrap();
            assertThat(bootstrap.creation().world().packageId()).isEqualTo("XUANXU");
            assertThat(bootstrap.creation().world().races()).extracting("raceId")
                    .containsExactly("race:xuanxu:human", "race:xuanxu:yao", "race:xuanxu:spirit");
            assertThat(bootstrap.creation().playPreferences().focus()).extracting("code")
                    .contains("EXPLORATION", "RELATIONSHIP", "CULTIVATION");
            JsonNode bootstrapJson = mapper.valueToTree(bootstrap);
            assertThat(bootstrapJson.has("worlds")).isFalse();
            assertThat(bootstrapJson.has("races")).isFalse();
            assertThat(bootstrapJson.path("creation").path("world").path("revision").asLong())
                    .isEqualTo(2);
            var created = engine.create(USER, createRequest(null, "stage-01"));

            assertThat(created.state().world().code()).isEqualTo("XUANXU");
            assertThat(created.state().protagonist().name()).isEqualTo("青璃");
            assertThat(created.state().protagonist().realm()).isEqualTo("开智初期");
            assertThat(created.state().choices()).hasSize(3);
            assertThat(created.state().inventory()).extracting("name").containsExactly("月纹石");
            assertThat(created.state().narrative()).doesNotContain("。。");
            assertThat(created.state().protagonist().storyPreference())
                    .contains("探索", "战斗适中", "节奏适中", "引导较少")
                    .doesNotContain("EXPLORATION", "RELATIONSHIP", "LOW", "EXPLICIT");
            assertThat(created.turnNumber()).isZero();
            assertThat(jdbc.queryForObject("select count(*) from story_turn where save_id=?",
                    Integer.class, created.saveId())).isZero();
            String stateJson = jdbc.queryForObject("select state_json from story_save where id=?",
                    String.class, created.saveId());
            JsonNode storedState = mapper.readTree(stateJson);
            assertThat(storedState.path("trustedProjection").path("creationContract").isObject()).isTrue();
            assertThat(storedState.path("trustedProjection").path("creationContract")
                    .path("realmBinding").path("startingRankKey").asText()).isEqualTo("L1");
            assertThat(storedState.path("trustedProjection").path("creationContract")
                    .path("realmBinding").path("startingPhase").asText()).isEqualTo("EARLY");
            assertThat(storedState.path("trustedProjection").path("protagonistCultivation")
                    .path("currentStageId").asText()).isEqualTo("realm:xuanxu:yao:l1");
            assertThat(storedState.path("trustedProjection").path("protagonistCultivation")
                    .path("requiredProgressMarks").asInt()).isEqualTo(2);
            assertThat(storedState.path("trustedProjection").has("initialContract")).isFalse();
            assertThat(storedState.has("protagonist")).isFalse();
            String startingEntityRef = storedState.path("trustedProjection").path("creationContract")
                    .path("origin").path("startingEntityRefs").get(0).asText();
            assertThat(jdbc.queryForObject("select count(*) from story_entity where id=? and save_id=?",
                    Integer.class, startingEntityRef, created.saveId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select source_type from story_save where id=?",
                    String.class, created.saveId())).isEqualTo("NATIVE");
        } finally {
            database.shutdown();
        }
    }

    @Test
    void preservesConfirmedOpeningThroughCreationAndFreshEngineReadback() throws Exception {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            PersistedLightNarrativeEngine engine = engine(database,
                    new StorySaveRepository(jdbc, mapper), new StoryEntityRepository(jdbc, mapper),
                    new StoryMemoryRepository(jdbc));
            String narrative = "晨雾压在石阶上。你握着月纹石，听见洞口传来族人的呼唤。\n\n"
                    + "对方停在雾里，等你决定是否走近。";
            var scene = new StoryApiModels.OpeningSceneSubmission("雾中来声", narrative, List.of(
                    new StoryApiModels.OpeningChoiceSubmission("先问来意", "留在石阶上，询问对方为何找来。"),
                    new StoryApiModels.OpeningChoiceSubmission("观察周围", "先看看洞口附近有没有其他动静。")));
            CreateSaveRequest request = withOpeningScene(scene);

            var created = engine.create(USER, request);

            assertThat(created.turnNumber()).isZero();
            assertThat(created.state().sceneTitle()).isEqualTo(scene.title());
            assertThat(created.state().narrative()).isEqualTo(narrative);
            assertThat(created.state().choices()).extracting(StoryApiModels.Choice::title)
                    .containsExactly("先问来意", "观察周围");
            assertThat(created.state().choices()).extracting(StoryApiModels.Choice::description)
                    .containsExactlyElementsOf(scene.choices().stream()
                            .map(StoryApiModels.OpeningChoiceSubmission::description).toList());
            assertThat(created.state().choices()).allSatisfy(choice -> assertThat(choice.id()).isNotBlank());
            JsonNode stored = mapper.readTree(jdbc.queryForObject(
                    "select state_json from story_save where id=?", String.class, created.saveId()));
            assertThat(stored.path("scene").path("title").asText()).isEqualTo(scene.title());
            assertThat(stored.path("scene").path("narrative").asText()).isEqualTo(narrative);
            assertThat(stored.path("scene").path("situation").asText()).isEqualTo(request.opening().situation());
            assertThat(stored.path("scene").path("choices"))
                    .isEqualTo(mapper.valueToTree(created.state().choices()));
            assertThat(stored.path("openingScene")).isEqualTo(stored.path("scene"));
            assertThat(new StoryConversationRepository(jdbc).summary(created.saveId())).isEqualTo(narrative);
            assertThat(jdbc.queryForObject("select summarized_through_turn from story_conversation where save_id=?",
                    Integer.class, created.saveId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from story_turn where save_id=?",
                    Integer.class, created.saveId())).isZero();

            JdbcTemplate reloadedJdbc = new JdbcTemplate(database);
            PersistedLightNarrativeEngine reloaded = engine(database,
                    new StorySaveRepository(reloadedJdbc, mapper), new StoryEntityRepository(reloadedJdbc, mapper),
                    new StoryMemoryRepository(reloadedJdbc));
            var readback = reloaded.get(USER, created.saveId());
            assertThat(readback.turnNumber()).isZero();
            assertThat(readback.state().sceneTitle()).isEqualTo(scene.title());
            assertThat(readback.state().narrative()).isEqualTo(narrative);
            assertThat(readback.state().choices()).isEqualTo(created.state().choices());
            assertThat(new StoryConversationRepository(reloadedJdbc).summary(created.saveId())).isEqualTo(narrative);
            var saved = new StorySaveRepository(reloadedJdbc, mapper).findOwned(USER.userId(), created.saveId())
                    .orElseThrow();
            assertThat(saved.state().path("openingScene")).isEqualTo(stored.path("openingScene"));
            var projections = new com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper(mapper);
            var projection = projections.load(saved,
                    new StoryEntityRepository(reloadedJdbc, mapper).listAll(created.saveId()),
                    new StoryMemoryRepository(reloadedJdbc).recentActive(created.saveId(), 20));
            ObjectNode nextState = projections.writeState(saved.state(), projection);
            nextState.withObject("scene").put("narrative", "你已走到洞口，眼前的场景发生变化。");
            assertThat(nextState.path("openingScene")).isEqualTo(stored.path("openingScene"));
            assertThat(nextState.path("scene").path("narrative").asText()).isNotEqualTo(narrative);
        } finally {
            database.shutdown();
        }
    }

    @Test
    void rejectsIncompleteConfirmedOpeningWithoutFallingBackOrWritingPartialSave() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            PersistedLightNarrativeEngine engine = engine(database,
                    new StorySaveRepository(jdbc, mapper), new StoryEntityRepository(jdbc, mapper),
                    new StoryMemoryRepository(jdbc));
            var invalid = new StoryApiModels.OpeningSceneSubmission("雾中来声", " ", List.of(
                    new StoryApiModels.OpeningChoiceSubmission("先问来意", "询问来意。")));

            assertThatThrownBy(() -> engine.create(USER, withOpeningScene(invalid)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("开场内容缺失");
            assertThat(jdbc.queryForObject("select count(*) from story_save", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from story_entity", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from story_memory", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from story_conversation", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from story_turn", Integer.class)).isZero();
        } finally {
            database.shutdown();
        }
    }

    @Test
    void replacesSaveInOneTransactionAndKeepsOldSaveWhenNewCreationIsInvalid() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            PersistedLightNarrativeEngine engine = engine(database,
                    new StorySaveRepository(jdbc, mapper), new StoryEntityRepository(jdbc, mapper),
                    new StoryMemoryRepository(jdbc));
            var original = engine.create(USER, createRequest(null, "stage-01"));

            assertThatThrownBy(() -> engine.create(USER, createRequest(original.saveId(), "stage-06")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("具体本相");
            assertThat(jdbc.queryForObject("select status from story_save where id=?", String.class,
                    original.saveId())).isEqualTo("ACTIVE");
            assertThat(engine.list(USER)).extracting("saveId").containsExactly(original.saveId());

            var replacement = engine.create(USER, createRequest(original.saveId(), "stage-02"));
            assertThat(replacement.saveId()).isNotEqualTo(original.saveId());
            assertThat(engine.list(USER)).extracting("saveId").containsExactly(replacement.saveId());
            assertThat(engine.list(USER).getFirst().slotNo()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select status from story_save where id=?", String.class,
                    original.saveId())).isEqualTo("ARCHIVED");
            assertThat(jdbc.queryForObject("select delete_reason from story_save where id=?", String.class,
                    original.saveId())).isEqualTo("CHARACTER_REPLACED");
        } finally {
            database.shutdown();
        }
    }

    private PersistedLightNarrativeEngine engine(EmbeddedDatabase database, StorySaveRepository saves,
                                                  StoryEntityRepository entities,
                                                  StoryMemoryRepository memories) {
        JdbcTemplate jdbc = new JdbcTemplate(database);
        GameVersionService releases = new GameVersionService(jdbc);
        StubModelRoutes modelRoutes = new StubModelRoutes();
        CharacterCreationCatalogService catalog = com.wenxian.game.story.worldpackage.WorldPackageRegistryTestSupport.catalog(mapper);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        StorySaveCreationService creation = new StorySaveCreationService(saves, entities, memories,
                new StoryConversationRepository(jdbc), catalog, new OpeningSceneService(),
                new ActiveSavePolicyService(jdbc), releases, transactions, mapper);
        return new PersistedLightNarrativeEngine(saves, new StoryTurnRepository(jdbc, mapper), entities,
                new StoryCorrectionRepository(jdbc, mapper),
                null, null, catalog, null, creation,
                new ActiveSavePolicyService(jdbc), null, releases,
                transactions, mapper,
                new StorySaveViewService(entities, memories, modelRoutes, mapper),
                new StoryTurnFailureMapper(modelRoutes));
    }

    private ObjectNode state() {
        ObjectNode state = mapper.createObjectNode();
        state.put("schemaVersion", 2);
        state.put("saveId", "story-lifecycle");
        state.put("turnNumber", 3);
        state.put("status", "ACTIVE");
        state.set("worldTime", mapper.valueToTree(com.wenxian.game.story.domain.StoryWorldTime.opening()));
        state.putObject("coreState").put("currentLocationRef", "region:xuanxu:qingqi")
                .put("currentLocationName", "青岐城").put("condition", "神完气足")
                .put("lifeGoal", "随经历形成");
        state.putObject("scene").put("title", "雨后入城").put("narrative", "你走入青岐城。")
                .put("situation", "雨后入城").putNull("pendingDecision").putArray("choices");
        CultivationSystem cultivation = cultivation("人族");
        return new com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper(mapper)
                .writeInitialCreation(state, contract("XUANXU", "玄墟界", "陆沉", "人族",
                        "race:xuanxu:human", "山村少年", "region:xuanxu:qingqi"),
                        PlayPreference.defaults(Instant.parse("2026-08-22T00:00:00Z")), cultivation,
                        new ProtagonistCultivation(cultivation.systemId(), "stage-01", ""));
    }

    private CreateSaveRequest createRequest(String replaceSaveId, String startingStageId) {
        String factType = "stage-06".equals(startingStageId) ? "PAST_EVENT" : "IDENTITY";
        return new CreateSaveRequest("XUANXU", 2,
                new StoryApiModels.CharacterIdentitySubmission("青璃", "FEMALE", "race:xuanxu:yao"),
                new StoryApiModels.CharacterOriginSubmission("苍梧石窟中苏醒的灵狐", List.of(
                        new StoryApiModels.OriginFactSubmission("origin:fact:1", factType,
                                "本相为月纹灵狐，在苍岭石窟中苏醒")), List.of(), List.of(
                        new StoryApiModels.StartingEntitySubmission("origin:entity:1", "ITEM", "月纹石",
                                "苏醒时就在身边的石片"))),
                new StoryApiModels.OpeningSubmission("region:xuanxu:cangling", "晨雾中第一次听见族人的呼唤"),
                new StoryApiModels.PlayPreferenceSubmission(List.of("EXPLORATION"), "BALANCED", "BALANCED",
                "LIGHT", ""), replaceSaveId);
    }

    private CreateSaveRequest withOpeningScene(StoryApiModels.OpeningSceneSubmission scene) {
        CreateSaveRequest original = createRequest(null, "stage-01");
        return new CreateSaveRequest(original.worldPackageId(), original.worldRevision(), original.identity(),
                original.origin(), new StoryApiModels.OpeningSubmission(original.opening().regionId(),
                original.opening().situation(), scene), original.playPreference(), original.replaceSaveId());
    }

    private CreationContract contract(String packageId, String worldName, String name, String raceName,
                                      String raceId, String origin, String regionId) {
        return new CreationContract(new CreationContract.WorldBinding(packageId, 1, worldName, "仙路难寻", regionId),
                new CreationContract.CharacterIdentity(name, "UNSPECIFIED", raceId, raceName),
                new CreationContract.CharacterOrigin(origin, List.of(new CreationContract.OriginFact(
                        "origin:fact:1", CreationContract.OriginFactType.PAST_EVENT, origin)), List.of(), List.of()),
                new CreationContract.Opening(regionId, "命卷初开"),
                new CreationContract.RealmBinding("realm-scale:test", 1, "L1",
                        com.wenxian.game.story.domain.WorldPackage.RealmPhase.EARLY));
    }

    private CultivationSystem cultivation(String race) {
        List<CultivationSystem.Stage> stages = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> new CultivationSystem.Stage("stage-0" + index, index, "境界" + index,
                        "第" + index + "阶段", List.of(), index == 6 ? List.of() : List.of("stage-0" + (index + 1))))
                .toList();
        return new CultivationSystem("cultivation-test", 1, "测试修行", "测试体系", race, "stage-01", stages,
                new CultivationSystem.Confirmation(CultivationSystem.ConfirmationSource.PLAYER_CONFIRMED_CREATION,
                        Instant.parse("2026-08-22T00:00:00Z")));
    }

    private EmbeddedDatabase database() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                USER.userId(), USER.email(), "ACTIVE", now, now);
        jdbc.update("insert into gameplay_config(config_key,config_value,updated_at) values (?,?,?)",
                ActiveSavePolicyService.CONFIG_KEY, "4", now);
        return database;
    }

    private static final class StubModelRoutes implements StoryModelSelectionGateway {
        @Override
        public StoryModelSelection currentSelection(String userId) {
            return new StoryModelSelection("TEST", "test-model", "PUBLIC");
        }

        @Override
        public void markCredentialInvalid(String userId, String provider, String reason) {
            // 生命周期测试不触发模型鉴权失败。
        }
    }
}

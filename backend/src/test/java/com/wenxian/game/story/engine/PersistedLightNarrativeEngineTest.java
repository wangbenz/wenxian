package com.wenxian.game.story.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.service.ActiveSavePolicyService;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.story.persistence.StoryConversationRepository;
import com.wenxian.game.story.persistence.StoryCorrectionRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository.StoredMemory;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewSave;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewTurn;
import com.wenxian.game.story.persistence.StorySaveRepository;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import com.wenxian.game.story.model.StoryApiModels.TurnRequest;
import com.wenxian.game.story.model.StoryApiModels.CorrectionRequest;
import com.wenxian.game.story.model.StoryApiModels.CreateSaveRequest;
import com.wenxian.game.v014.model.V014ApiModels.ModelPreference;
import com.wenxian.game.v014.model.V014State;
import com.wenxian.game.v014.service.V014ModelPreferenceService;
import com.wenxian.game.v014.service.V014StateReducer;
import com.wenxian.game.v014.service.V014WorldCatalog;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersistedLightNarrativeEngineTest {
    private static final UserIdentity USER = new UserIdentity("story-user", "story@test.local", true);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void readsMigratedCoreEntitiesAndMemoriesWithoutLegacyTables() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            saves.insert(new NewSave("story-v014-test", USER.userId(), 1, "ACTIVE", "XUANXU",
                    "LIGHT_NARRATIVE", 1, 1, "MIGRATED_V014", "V0.14", "v014-save-test", null,
                    core(), 3, "V0.14", "V0.14", Instant.now()));
            ObjectNode attributes = mapper.createObjectNode();
            attributes.put("itemType", "信物");
            attributes.put("source", "旧识所赠");
            attributes.put("quantity", 1);
            attributes.put("inPossession", true);
            entities.insert(new Entity("entity-token", "story-v014-test", "ITEM", "青纹令", "随身携带",
                    true, "来自旧识的令牌", attributes, 0, 3, Instant.now(), Instant.now()));
            memories.insert("story-v014-test", new StoredMemory("memory-origin", "此生来处", "山村少年",
                    0, 0, "KEY"));
            jdbc.update("""
                    insert into story_conversation(save_id,rolling_summary,summarized_through_turn,
                        summary_schema_version,updated_at) values (?,?,?,?,?)
                    """, "story-v014-test", "你已经进入青岐城。", 3, 1, Timestamp.from(Instant.now()));

            PersistedLightNarrativeEngine engine = engine(database, saves, entities, memories);

            assertTrue(engine.supports("story-v014-test"));
            assertEquals(1, engine.list(USER).size());
            var result = engine.get(USER, "story-v014-test");
            assertEquals("PLAYABLE", result.mode());
            assertEquals(3, result.turnNumber());
            assertEquals("陆沉", result.state().protagonist().name());
            assertEquals("青纹令", result.state().inventory().getFirst().name());
            assertEquals("此生来处", result.state().memories().getFirst().title());

            StoryTurnRepository turns = new StoryTurnRepository(jdbc, mapper);
            turns.insert(new NewTurn("turn-existing", "story-v014-test", "request-existing", 3,
                    "查看令牌", "你已经查看令牌。", mapper.createArrayNode(), mapper.createObjectNode(),
                    120, 180, "LIGHT_NARRATIVE", 1, "story-test", "DEEPSEEK", "test-model", "PUBLIC",
                    10, 20, 0, 0, 30, "V0.14", Instant.now()));
            var retried = engine.takeTurn(USER, "story-v014-test",
                    new TurnRequest("查看令牌", "request-existing", 99));
            assertEquals(3, retried.turnNumber(), "已提交 requestId 必须先于旧 revision 返回现有结果");

            var removed = engine.removeEntity(USER, "story-v014-test", "entity-token", 1);
            assertEquals(2, removed.revision());
            assertTrue(removed.state().inventory().isEmpty());

            var corrected = engine.correct(USER, "story-v014-test",
                    new CorrectionRequest("地点应为青岐山门", 2));
            assertEquals(3, corrected.revision());
            assertEquals("青岐山门", corrected.state().currentLocation());

            engine.archive(USER, "story-v014-test");
            var archived = engine.get(USER, "story-v014-test");
            assertEquals("ARCHIVE", archived.mode());
            assertEquals(List.of("READ"), archived.capabilities());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void runtimeSwitchKeepsGenericCandidatesInvisibleWhenDisabled() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            PersistedLightNarrativeEngine engine = engine(database, saves, entities, memories, false);

            assertFalse(engine.supports("story-v014-test"));
            assertTrue(engine.list(USER).isEmpty());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void createsInUnifiedTablesAndArchivedHistoryDoesNotConsumeActiveLimit() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            saves.insert(new NewSave("story-existing-active", USER.userId(), 1, "ACTIVE", "XUANXU",
                    "LIGHT_NARRATIVE", 1, 1, "NATIVE", "V0.14", null, null,
                    core(), 3, "V0.14", "V0.14", Instant.now()));
            for (int slot = 2; slot <= 14; slot++) {
                saves.insert(new NewSave("story-archive-" + slot, USER.userId(), slot, "ARCHIVED", "XUANXU",
                        "LIGHT_NARRATIVE", 1, 1, "NATIVE", "V0.14", null, null,
                        core(), slot, "V0.14", "V0.14", Instant.now()));
            }
            PersistedLightNarrativeEngine engine = engine(database, saves, entities, memories);
            CreateSaveRequest request = new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索");

            var created = engine.create(USER, request);

            assertTrue(created.saveId().startsWith("story-"));
            assertEquals(15, saves.findOwned(USER.userId(), created.saveId()).orElseThrow().slotNo());
            assertEquals(2, saves.countActive(USER.userId()));
            assertEquals("楚星河", created.state().protagonist().name());
            GameApiException error = assertThrows(GameApiException.class, () -> engine.create(USER, request));
            assertEquals("ACTIVE_SAVE_LIMIT_REACHED", error.code());
        } finally {
            database.shutdown();
        }
    }

    private PersistedLightNarrativeEngine engine(EmbeddedDatabase database, StorySaveRepository saves,
                                                  StoryEntityRepository entities,
                                                  StoryMemoryRepository memories) {
        return engine(database, saves, entities, memories, true);
    }

    private PersistedLightNarrativeEngine engine(EmbeddedDatabase database, StorySaveRepository saves,
                                                  StoryEntityRepository entities,
                                                  StoryMemoryRepository memories, boolean enabled) {
        JdbcTemplate jdbc = new JdbcTemplate(database);
        ActiveSavePolicyService savePolicy = new ActiveSavePolicyService(jdbc);
        return new PersistedLightNarrativeEngine(saves, new StoryTurnRepository(jdbc, mapper), entities, memories,
                new StoryConversationRepository(jdbc), new StoryCorrectionRepository(jdbc, mapper), null,
                new V014StateReducer(), new StubModels(), new V014WorldCatalog(false), null, savePolicy,
                null, new GameVersionService(jdbc),
                new TransactionTemplate(new DataSourceTransactionManager(database)), mapper, enabled,
                "https://example.com");
    }

    private EmbeddedDatabase database() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                USER.userId(), USER.email(), "ACTIVE", now, now);
        jdbc.update("insert into gameplay_config(config_key,config_value,updated_at) values (?,?,?)",
                ActiveSavePolicyService.CONFIG_KEY, "2", now);
        return database;
    }

    private ObjectNode core() {
        V014State.WorldProfile world = new V014State.WorldProfile("XUANXU", "玄墟界", "OFFICIAL",
                "仙路难寻", "轻叙事", "青岐州");
        V014State.Protagonist protagonist = new V014State.Protagonist("陆沉", "人族", "男", "山村少年",
                "引气", "随经历形成", "自由探索");
        ObjectNode core = mapper.createObjectNode();
        core.put("schemaVersion", 1);
        core.put("saveId", "story-v014-test");
        core.put("turnNumber", 3);
        core.put("status", "ACTIVE");
        core.set("world", mapper.valueToTree(world));
        core.set("protagonist", mapper.valueToTree(protagonist));
        core.put("worldTimeMinutes", 180);
        core.put("currentLocation", "青岐城");
        core.put("condition", "神完气足");
        core.put("sceneTitle", "雨后入城");
        core.put("narrative", "你走入青岐城。");
        core.set("choices", mapper.valueToTree(List.of(
                new V014State.Choice("choice-test", "查看令牌", "辨认来历"))));
        return core;
    }

    private static final class StubModels extends V014ModelPreferenceService {
        private StubModels() {
            super(null, null);
        }

        @Override
        public ModelPreference get(String userId) {
            return new ModelPreference("DEEPSEEK", "test-model", "PUBLIC");
        }
    }
}

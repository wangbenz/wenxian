package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewSave;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewTurn;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryRepositoriesTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void saveUsesNeutralEngineMetadataAndOptimisticRevision() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            saves.insert(newSave("story-save-1"));

            var stored = saves.findOwned("story-user", "story-save-1").orElseThrow();
            assertEquals("LIGHT_NARRATIVE", stored.engineCode());
            assertEquals(1, stored.stateSchemaVersion());
            assertEquals("陆沉", stored.state().path("protagonist").path("name").asText());

            ObjectNode changed = state("陆沉");
            changed.put("location", "青岐山门");
            assertEquals(2, saves.updateState("story-user", "story-save-1", 1, changed,
                    1, 1, "XUANXU", "ACTIVE", "V0.14"));
            assertThrows(OptimisticLockingFailureException.class,
                    () -> saves.updateState("story-user", "story-save-1", 1, changed,
                            1, 2, "XUANXU", "ACTIVE", "V0.14"));
            assertEquals(1, saves.listOwned("story-user").size());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void turnRequestIsIdempotentAndEntityCleanupIsSoft() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            new StorySaveRepository(jdbc, mapper).insert(newSave("story-save-2"));
            StoryTurnRepository turns = new StoryTurnRepository(jdbc, mapper);
            NewTurn turn = turn("turn-1", "request-1");
            turns.insert(turn);

            assertEquals("进入山门", turns.findByRequestId("story-save-2", "request-1")
                    .orElseThrow().playerAction());
            assertThrows(DataIntegrityViolationException.class,
                    () -> turns.insert(turn("turn-2", "request-1")));
            assertEquals(1, turns.recent("story-save-2", 2).size());

            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            entities.insert(new Entity("entity-token", "story-save-2", "ITEM", "青纹令", "随身携带",
                    true, "来自旧识的令牌", mapper.createObjectNode().put("quantity", 1),
                    1, 1, Instant.now(), Instant.now()));
            assertEquals(1, entities.listVisible("story-save-2").size());
            assertEquals(1, entities.hide("story-save-2", "entity-token", 2, "已移出行囊"));
            assertTrue(entities.listVisible("story-save-2").isEmpty());
            assertEquals("entity-token", entities.listAll("story-save-2").getFirst().id(),
                    "权威读取必须保留已隐藏实体，避免后续归并重复创建同一身份");
            assertEquals(1, jdbc.queryForObject("select count(*) from story_entity", Integer.class),
                    "整理实体只隐藏当前视图，不能删除历史身份");
        } finally {
            database.shutdown();
        }
    }

    @Test
    void saveOwnershipPreventsCrossAccountReads() {
        EmbeddedDatabase database = database();
        try {
            StorySaveRepository saves = new StorySaveRepository(new JdbcTemplate(database), mapper);
            saves.insert(newSave("story-save-3"));

            assertFalse(saves.findOwned("other-user", "story-save-3").isPresent());
        } finally {
            database.shutdown();
        }
    }

    private EmbeddedDatabase database() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                "story-user", "story@test.local", "ACTIVE", now, now);
        return database;
    }

    private NewSave newSave(String saveId) {
        return new NewSave(saveId, "story-user", 1, "ACTIVE", "XUANXU",
                "LIGHT_NARRATIVE", 1, 1, "NATIVE", "V0.14", null, null,
                state("陆沉"), 0, "V0.14", "V0.14", Instant.now());
    }

    private NewTurn turn(String id, String requestId) {
        return new NewTurn(id, "story-save-2", requestId, 1, "进入山门", "你踏入山门。",
                mapper.createArrayNode().add("继续前行"), mapper.createObjectNode().put("location", "青岐山门"),
                0, 60, "LIGHT_NARRATIVE", 1, "story-test-1", "DEEPSEEK", "test-model",
                "PUBLIC", 10, 20, 4, 6, 30, "V0.14", Instant.now());
    }

    private ObjectNode state(String name) {
        ObjectNode state = mapper.createObjectNode();
        state.putObject("protagonist").put("name", name);
        state.put("location", "青岐州");
        return state;
    }
}

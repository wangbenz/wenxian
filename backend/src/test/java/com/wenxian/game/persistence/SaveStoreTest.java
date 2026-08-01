package com.wenxian.game.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.engine.GameCatalog;
import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveStoreTest {
    @Test
    void upgradesALegacySingleSaveSchemaWithoutLosingTheExistingSave() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.execute("""
                    create table game_save(
                      id varchar(80) primary key,
                      user_id varchar(80) not null unique,
                      state_json clob not null,
                      revision bigint not null,
                      updated_at timestamp not null)
                    """);
            jdbc.execute("""
                    create table game_life_archive(
                      id varchar(80) primary key,
                      user_id varchar(80) not null,
                      original_save_id varchar(80) not null,
                      state_json clob not null,
                      ended_at timestamp not null)
                    """);
            jdbc.update("insert into game_save values ('legacy-save','legacy-user','{}',0,?)",
                    Timestamp.from(Instant.now()));

            SaveStore store = new SaveStore(jdbc, new ObjectMapper().findAndRegisterModules());
            store.ensureArchiveEndReasonColumn();

            assertEquals(1, store.find("legacy-save").slotNo);
            GameState second = new GameState();
            store.put("new-save", "legacy-user", 2, second, 0);
            assertEquals(2, store.countCurrentForUser("legacy-user"));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void persistsAndReloadsAuthoritativeSnapshot() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql")
                .build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.update("""
                    insert into app_user(id, email, status, registered_at, last_login_at)
                    values ('user-test', 'test@example.com', 'ACTIVE', ?, ?)
                    """, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            SaveStore store = new SaveStore(jdbc, mapper);
            AgentConversationStore conversations = new AgentConversationStore(jdbc, mapper);
            GameState state = new GameState();
            state.world = GameCatalog.fixedWorld();
            state.phase = "playing";
            state.sceneId = "persisted-scene";
            store.put("persist-test-save", "user-test", state, 2);
            String conversationId = conversations.ensure("persist-test-save", "deepseek-v4-pro");
            conversations.append(conversationId, 0, "user", "选择命格", null, 0, 0);
            conversations.append(conversationId, 0, "assistant", "命卷开启", "response-1", 10, 20);
            jdbc.update("""
                    insert into game_turn(
                        id, save_id, conversation_id, turn_number, player_action,
                        narrative_json, choices_json, state_delta_json,
                        world_time_before, world_time_after, model, response_id,
                        prompt_tokens, completion_tokens, latency_ms, created_at)
                    values ('turn-test', 'persist-test-save', ?, 1, '向前行走',
                        '[]', '[]', '{}', 0, 60, 'deepseek-v4-pro', 'response-1',
                        10, 20, 800, ?)
                    """, conversationId, Timestamp.from(Instant.now()));

            // Stored transcripts remain auditable, but are not replayed into the model when
            // the authoritative state has no older timeline events to summarize.
            assertEquals(0, conversations.loadHistory("persist-test-save", state).size());
            assertTrue(conversationId.startsWith("conversation-"));

            SaveStore.StoredSave restored = store.findOwned("persist-test-save", "user-test");

            assertEquals("persisted-scene", restored.state.sceneId);
            assertEquals(2, restored.revision);
            assertEquals("user-test", restored.userId);

            restored.state.phase = "dead";
            SaveStore.StoredSave dead = store.put("persist-test-save", "user-test", restored.state, 3);
            store.archiveAndDelete(dead);

            assertEquals(0, jdbc.queryForObject("select count(*) from game_save", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from game_life_archive", Integer.class));
            assertEquals("RESTARTED", jdbc.queryForObject(
                    "select end_reason from game_life_archive", String.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from game_life_turn_archive", Integer.class));
            assertEquals("向前行走", jdbc.queryForObject(
                    "select player_action from game_life_turn_archive", String.class));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void keepsTwoIndependentActiveSlotsForOneAccount() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql")
                .build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.update("""
                    insert into app_user(id, email, status, registered_at, last_login_at)
                    values ('user-two', 'two@example.com', 'ACTIVE', ?, ?)
                    """, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            SaveStore store = new SaveStore(jdbc, new ObjectMapper().findAndRegisterModules());
            GameState first = new GameState();
            first.sceneTitle = "第一世";
            GameState second = new GameState();
            second.sceneTitle = "第二世";

            store.put("save-first", "user-two", 1, first, 0);
            store.put("save-second", "user-two", 2, second, 0);

            assertEquals(2, store.countCurrentForUser("user-two"));
            assertEquals(2, store.findAllCurrentForUser("user-two").size());
            assertEquals("第一世", store.find("save-first").state.sceneTitle);
            assertEquals("第二世", store.find("save-second").state.sceneTitle);
            assertEquals(0, store.nextAvailableSlot("user-two", 2));

            store.archiveAndDelete(store.find("save-first"), "ABANDONED");
            assertEquals(1, store.nextAvailableSlot("user-two", 2));
            assertEquals("ABANDONED", jdbc.queryForObject(
                    "select end_reason from game_life_archive", String.class));
        } finally {
            database.shutdown();
        }
    }
}

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

            assertEquals(2, conversations.loadHistory("persist-test-save", state).size());
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
        } finally {
            database.shutdown();
        }
    }
}

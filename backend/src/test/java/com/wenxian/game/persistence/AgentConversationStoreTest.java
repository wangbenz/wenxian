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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConversationStoreTest {

    @Test
    void summarizesLongHistoryAndKeepsOnlyRecentCompleteMessages() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql")
                .build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            Instant now = Instant.now();
            jdbc.update("""
                    insert into app_user(id, email, status, registered_at, last_login_at)
                    values ('user-context', 'context@example.com', 'ACTIVE', ?, ?)
                    """, Timestamp.from(now), Timestamp.from(now));
            jdbc.update("""
                    insert into game_save(id, user_id, state_json, revision, updated_at)
                    values ('save-context', 'user-context', '{}', 1, ?)
                    """, Timestamp.from(now));

            AgentConversationStore store = new AgentConversationStore(
                    jdbc, new ObjectMapper().findAndRegisterModules());
            String conversationId = store.ensure("save-context", "deepseek-v4-pro");
            for (int turn = 0; turn < 12; turn++) {
                store.append(conversationId, turn, "user", "行动-" + turn, null, 0, 0);
                store.append(conversationId, turn, "assistant",
                        "章节-" + turn + "-" + "墨".repeat(4_500), null, 0, 0);
            }

            GameState state = new GameState();
            state.world = GameCatalog.fixedWorld();
            state.worldYear = 3001;
            state.day = 3;
            state.minute = 600;
            state.currentLocation = "青木镇";
            List<Map<String, Object>> history = store.loadHistory("save-context", state);
            String combined = history.stream().map(message -> String.valueOf(message.get("content")))
                    .reduce("", (left, right) -> left + right);

            assertTrue(String.valueOf(history.getFirst().get("content")).contains("此前命卷摘要"));
            assertTrue(history.size() <= 17);
            assertTrue(combined.length() < 32_000);
            assertTrue(combined.contains("章节-11"));
            assertFalse(combined.contains("章节-0-"));
            assertTrue(jdbc.queryForObject(
                    "select char_length(summary) > 0 from agent_conversation where id = ?",
                    Boolean.class, conversationId));
        } finally {
            database.shutdown();
        }
    }
}

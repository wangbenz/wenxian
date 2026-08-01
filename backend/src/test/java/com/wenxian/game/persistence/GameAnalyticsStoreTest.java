package com.wenxian.game.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.service.GameVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameAnalyticsStoreTest {
    @Test
    void recordsTheCurrentVersionWithoutNarrativeContent() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql")
                .build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.update("insert into gameplay_config values ('current_game_version','V0.13',?)",
                    Timestamp.from(Instant.now()));
            GameAnalyticsStore store = new GameAnalyticsStore(jdbc, new ObjectMapper(),
                    new GameVersionService(jdbc));

            store.record("user-test", "save-test", "TURN_COMPLETED", Map.of("turn", 3));

            assertEquals("V0.13", jdbc.queryForObject(
                    "select game_version from game_analytics_event where save_id='save-test'", String.class));
            assertEquals("{\"turn\":3}", jdbc.queryForObject(
                    "select metadata_json from game_analytics_event where save_id='save-test'", String.class));
        } finally {
            database.shutdown();
        }
    }
}

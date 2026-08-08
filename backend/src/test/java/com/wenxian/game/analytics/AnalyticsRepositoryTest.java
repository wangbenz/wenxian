package com.wenxian.game.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsRepositoryTest {

    @Test
    void writesIdempotentFactsAndCountsOnlyMeaningfulActivity() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            AnalyticsRepository repository = new AnalyticsRepository(jdbc, new ObjectMapper());
            Instant now = Instant.parse("2026-08-08T10:00:00Z");
            AnalyticsEvent turn = new AnalyticsEvent("turn:1", AnalyticsEventType.TURN_SUCCEEDED,
                    "user-1", "save-1", "V0.14", "LIGHT_NARRATIVE", "REALTIME",
                    Map.of("turnNumber", 1), now);

            assertTrue(repository.append(turn));
            assertFalse(repository.append(turn));
            assertEquals(1, jdbc.queryForObject(
                    "select successful_turn_count from analytics_user_day where user_id='user-1'", Integer.class));

            repository.append(new AnalyticsEvent("correction:1", AnalyticsEventType.CORRECTION_APPLIED,
                    "user-1", "save-1", "V0.14", "LIGHT_NARRATIVE", "REALTIME", Map.of(), now));
            repository.append(new AnalyticsEvent("removal:1", AnalyticsEventType.ENTITY_REMOVED,
                    "user-1", "save-1", "V0.14", "LIGHT_NARRATIVE", "REALTIME", Map.of(), now));
            assertEquals(1, jdbc.queryForObject(
                    "select correction_count from analytics_user_day where user_id='user-1'", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "select entity_removal_count from analytics_user_day where user_id='user-1'", Integer.class));

            repository.append(new AnalyticsEvent("register:2", AnalyticsEventType.USER_REGISTERED,
                    "user-2", null, "V0.14", "", "REALTIME", Map.of(), now));
            assertEquals(0, jdbc.queryForObject(
                    "select count(*) from analytics_user_day where user_id='user-2'", Integer.class));
        } finally {
            database.shutdown();
        }
    }
}

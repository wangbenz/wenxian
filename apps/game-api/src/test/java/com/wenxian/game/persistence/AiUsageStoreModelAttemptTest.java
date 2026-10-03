package com.wenxian.game.persistence;

import com.wenxian.game.service.GameVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AiUsageStoreModelAttemptTest {

    @Test
    void recordsRequestCorrelationAndFallbackMetadata() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.update("insert into gameplay_config values ('current_game_version','V0.16',?)",
                    Timestamp.from(Instant.now()));
            AiUsageStore store = new AiUsageStore(jdbc, new GameVersionService(jdbc));

            store.recordModelAttempt("request-1", "user-1", 2, "CHARACTER_CREATION",
                    "DEEPSEEK", "PLATFORM", "flash", true, "SUCCESS",
                    120, 30, 80, 40, 900, "");

            var row = jdbc.queryForMap("select * from ai_usage_log where request_id='request-1'");
            assertThat(row.get("USER_ID")).isEqualTo("user-1");
            assertThat(row.get("ATTRIBUTION_STATUS")).isEqualTo("ACCOUNT_LINKED");
            assertThat(row.get("ATTEMPT_NUMBER")).isEqualTo(2);
            assertThat(row.get("FALLBACK_USED")).isEqualTo(true);
            assertThat(row.get("OPERATION")).isEqualTo("CHARACTER_CREATION");
            assertThat(row.get("CACHE_HIT_TOKENS")).isEqualTo(80);
            assertThat(row.get("CACHE_MISS_TOKENS")).isEqualTo(40);
        } finally {
            database.shutdown();
        }
    }
}

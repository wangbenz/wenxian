package com.wenxian.game.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OperationErrorStoreTest {
    @Test
    void recordsSafeDiagnosticAndRedactsApiKey() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            new OperationErrorStore(jdbc).record("WX-20260802-ABC123", "save-1",
                    "POST /api/v1/saves/save-1/turns", "SYSTEM", "CHAPTER_GENERATION_FAILED",
                    502, "本次推演未能完成", "provider rejected sk-test-secret-123456789");

            var row = jdbc.queryForMap("select * from operation_error where reference_id=?",
                    "WX-20260802-ABC123");
            assertEquals("save-1", row.get("SAVE_ID"));
            assertEquals("CHAPTER_GENERATION_FAILED", row.get("ERROR_CODE"));
            assertFalse(String.valueOf(row.get("DIAGNOSTIC")).contains("sk-test-secret"));
        } finally {
            database.shutdown();
        }
    }
}

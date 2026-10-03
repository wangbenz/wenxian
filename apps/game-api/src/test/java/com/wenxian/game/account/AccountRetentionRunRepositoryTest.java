package com.wenxian.game.account;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AccountRetentionRunRepositoryTest {
    @Test
    void recordsSuccessfulRunWithoutAccountLevelDetails() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            AccountRetentionRunRepository repository = new AccountRetentionRunRepository(jdbc);
            String runId = repository.start("SCHEDULED", settings(), Instant.parse("2026-08-29T00:00:00Z"));

            repository.complete(runId, new AccountRetentionPolicyService.PolicyRun(true, 2, 1, 1),
                    Instant.parse("2026-08-29T00:00:05Z"));

            var run = jdbc.queryForMap("select * from account_retention_run where id=?", runId);
            assertThat(run.get("STATUS")).isEqualTo("SUCCEEDED");
            assertThat(run.get("POLICY_EVALUATION_ENABLED")).isEqualTo(true);
            assertThat(run.get("NOTIFICATION_DELIVERY_ENABLED")).isEqualTo(true);
            assertThat(run.get("AUTOMATIC_DELETION_ENABLED")).isEqualTo(false);
            assertThat(run.get("NOTICES_PREPARED")).isEqualTo(2);
            assertThat(run.get("NOTICES_DELIVERED")).isEqualTo(1);
            assertThat(run.get("DELETION_JOBS_PREPARED")).isEqualTo(1);
        } finally {
            database.shutdown();
        }
    }

    @Test
    void recordsOnlyStableFailureCodeAndNoRawMessage() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            AccountRetentionRunRepository repository = new AccountRetentionRunRepository(jdbc);
            String runId = repository.start("SCHEDULED", settings(), Instant.parse("2026-08-29T00:00:00Z"));

            repository.fail(runId, new IllegalStateException("private@example.invalid"),
                    Instant.parse("2026-08-29T00:00:05Z"));

            var run = jdbc.queryForMap("select status,error_code from account_retention_run where id=?", runId);
            assertThat(run.get("STATUS")).isEqualTo("FAILED");
            assertThat(run.get("ERROR_CODE")).isEqualTo("ILLEGALSTATEEXCEPTION");
            assertThat(run.toString()).doesNotContain("private@example.invalid");
        } finally {
            database.shutdown();
        }
    }

    private AccountRetentionRunRepository.RunSettings settings() {
        return new AccountRetentionRunRepository.RunSettings(true, true, false, true, 23, 30, 7, 50);
    }

    private EmbeddedDatabase database() {
        return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
    }
}

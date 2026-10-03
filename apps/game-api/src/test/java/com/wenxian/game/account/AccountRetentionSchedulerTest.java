package com.wenxian.game.account;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.assertj.core.api.Assertions.assertThat;

class AccountRetentionSchedulerTest {
    @Test
    void persistsTheEffectiveRuntimeSwitchesForEveryScheduledRun() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            AccountRetentionPolicyService policy = new AccountRetentionPolicyService(jdbc, message -> { });
            AccountRetentionScheduler scheduler = new AccountRetentionScheduler(
                    policy, new AccountRetentionRunRepository(jdbc), 50, false);

            scheduler.run();

            var run = jdbc.queryForMap("select * from account_retention_run");
            assertThat(run.get("STATUS")).isEqualTo("SUCCEEDED");
            assertThat(run.get("POLICY_EVALUATION_ENABLED")).isEqualTo(true);
            assertThat(run.get("NOTIFICATION_DELIVERY_ENABLED")).isEqualTo(true);
            assertThat(run.get("AUTOMATIC_DELETION_ENABLED")).isEqualTo(false);
            assertThat(run.get("MAIL_GATEWAY_READY")).isEqualTo(true);
            assertThat(run.get("BATCH_SIZE")).isEqualTo(50);
            assertThat(run.get("FINISHED_AT")).isNotNull();
        } finally {
            database.shutdown();
        }
    }
}

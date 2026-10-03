package com.wenxian.game.account;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AccountActivityServiceTest {
    @Test
    void newerMeaningfulActivityCancelsPendingDeletionWithoutAllowingOlderEventsToRegressTime() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            Instant first = Instant.parse("2026-08-01T00:00:00Z");
            Instant resumed = Instant.parse("2026-08-29T00:00:00Z");
            jdbc.update("""
                    insert into app_user(id,email,status,registered_at,last_login_at)
                    values ('activity-user','activity@test.invalid','ACTIVE',?,?)
                    """, Timestamp.from(first), Timestamp.from(first));
            AccountActivityService service = new AccountActivityService(jdbc);
            service.record("activity-user", AccountActivitySource.LOGIN, first);
            jdbc.update("""
                    update account_retention_state
                    set policy_state='NOTICE_SENT',notice_sent_at=?,scheduled_deletion_at=?,updated_at=?
                    where user_id='activity-user'
                    """, Timestamp.from(first.plusSeconds(23 * 86400L)),
                    Timestamp.from(first.plusSeconds(30 * 86400L)), Timestamp.from(first));
            jdbc.update("""
                    insert into account_deletion_job(
                        id,user_id,subject_digest,reason,status,delete_counts_json,requested_at,updated_at
                    ) values ('activity-job','activity-user','digest','INACTIVITY','NOTICE_SENT','{}',?,?)
                    """, Timestamp.from(first), Timestamp.from(first));
            jdbc.update("""
                    insert into account_deletion_notice(
                        id,user_id,activity_version,scheduled_deletion_at,status,created_at,updated_at
                    ) values ('activity-notice','activity-user',0,?,'SENT',?,?)
                    """, Timestamp.from(first.plusSeconds(30 * 86400L)), Timestamp.from(first), Timestamp.from(first));

            service.record("activity-user", AccountActivitySource.TURN_COMMITTED, resumed);
            service.record("activity-user", AccountActivitySource.LOGIN, first.plusSeconds(60));

            var state = jdbc.queryForMap("""
                    select activity_source,policy_state,last_meaningful_activity_at,notice_sent_at,
                           scheduled_deletion_at,canceled_at
                    from account_retention_state where user_id='activity-user'
                    """);
            assertThat(state.get("ACTIVITY_SOURCE")).isEqualTo("TURN_COMMITTED");
            assertThat(((Timestamp) state.get("LAST_MEANINGFUL_ACTIVITY_AT")).toInstant()).isEqualTo(resumed);
            assertThat(state.get("POLICY_STATE")).isEqualTo("ACTIVE");
            assertThat(state.get("NOTICE_SENT_AT")).isNull();
            assertThat(state.get("SCHEDULED_DELETION_AT")).isNull();
            assertThat(state.get("CANCELED_AT")).isNotNull();
            assertThat(jdbc.queryForObject("select status from account_deletion_job where id='activity-job'",
                    String.class)).isEqualTo("CANCELED");
            assertThat(jdbc.queryForObject("select user_id from account_deletion_job where id='activity-job'",
                    String.class)).isNull();
            assertThat(jdbc.queryForObject("select status from account_deletion_notice where id='activity-notice'",
                    String.class)).isEqualTo("CANCELED");
            assertThat(jdbc.queryForObject("select user_id from account_deletion_notice where id='activity-notice'",
                    String.class)).isNull();
            assertThat(jdbc.queryForList(
                    "select event_type from account_lifecycle_event", String.class))
                    .containsExactlyInAnyOrder("NOTICE_CANCELED", "DELETION_CANCELED");
            assertThat(jdbc.queryForList(
                    "select metadata_json from account_lifecycle_event", String.class))
                    .allSatisfy(metadata -> assertThat(metadata)
                            .contains("TURN_COMMITTED")
                            .doesNotContain("activity-user", "activity@test.invalid"));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void staleActivityCannotCancelAnExistingNoticeOrDeletionJob() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            Instant current = Instant.parse("2026-08-20T00:00:00Z");
            Instant stale = Instant.parse("2026-08-01T00:00:00Z");
            Timestamp timestamp = Timestamp.from(current);
            jdbc.update("""
                    insert into app_user(id,email,status,registered_at,last_login_at)
                    values ('stale-user','stale@test.invalid','ACTIVE',?,?)
                    """, Timestamp.from(stale), timestamp);
            AccountActivityService service = new AccountActivityService(jdbc);
            service.record("stale-user", AccountActivitySource.LOGIN, current);
            jdbc.update("""
                    update account_retention_state
                    set policy_state='NOTICE_SENT',notice_sent_at=?,scheduled_deletion_at=?
                    where user_id='stale-user'
                    """, timestamp, Timestamp.from(current.plusSeconds(7 * 86400L)));
            jdbc.update("""
                    insert into account_deletion_notice(
                        id,user_id,activity_version,scheduled_deletion_at,status,created_at,updated_at,sent_at
                    ) values ('stale-notice','stale-user',0,?,'SENT',?,?,?)
                    """, Timestamp.from(current.plusSeconds(7 * 86400L)), timestamp, timestamp, timestamp);
            jdbc.update("""
                    insert into account_deletion_job(
                        id,user_id,subject_digest,reason,status,delete_counts_json,requested_at,updated_at
                    ) values ('stale-job','stale-user','digest','INACTIVITY','NOTICE_SENT','{}',?,?)
                    """, timestamp, timestamp);

            service.record("stale-user", AccountActivitySource.LOGIN, stale);

            assertThat(jdbc.queryForObject("select policy_state from account_retention_state", String.class))
                    .isEqualTo("NOTICE_SENT");
            assertThat(jdbc.queryForObject("select status from account_deletion_notice", String.class))
                    .isEqualTo("SENT");
            assertThat(jdbc.queryForObject("select status from account_deletion_job", String.class))
                    .isEqualTo("NOTICE_SENT");
            assertThat(jdbc.queryForObject("select count(*) from account_lifecycle_event", Integer.class))
                    .isZero();
        } finally {
            database.shutdown();
        }
    }
}

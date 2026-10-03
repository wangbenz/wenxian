package com.wenxian.admin.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountLifecycleQueryTest {
    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private AccountLifecycleApplicationService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("account-lifecycle-query-" + UUID.randomUUID() + ";MODE=MySQL").build();
        jdbc = new JdbcTemplate(database);
        createSchema();
        insertFacts();
        var repository = new JdbcAccountLifecycleRepository(jdbc, new ObjectMapper());
        service = new AccountLifecycleApplicationService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void reportsOverviewQueuesAndObservedRuntimeWithoutSensitiveFields() {
        var overview = service.overview();
        assertThat(overview.retentionStates()).containsEntry("ACTIVE", 1L).containsEntry("NOTICE_SENT", 1L);
        assertThat(overview.noticeStates()).containsEntry("SENT", 1L);
        assertThat(overview.deletionJobStates()).containsEntry("DELETE_DUE", 1L);
        assertThat(overview.dueWithin24Hours()).isOne();
        assertThat(overview.dueWithin7Days()).isOne();
        assertThat(overview.unresolvedModelUsage()).isOne();

        var notices = service.notices("sent", 0, 20);
        assertThat(notices.total()).isOne();
        assertThat(notices.rows().getFirst().userId()).isEqualTo("current-user");
        assertThat(notices.rows().getFirst().toString()).doesNotContain("subject-digest", "player@example.invalid");

        var jobs = service.deletionJobs("delete_due", "inactivity", 0, 20);
        assertThat(jobs.total()).isOne();
        assertThat(jobs.rows().getFirst().userId()).isEqualTo("current-user");
        assertThat(jobs.rows().getFirst().toString()).doesNotContain("subject-digest", "player@example.invalid");

        var runtime = service.runtimeStatus();
        assertThat(runtime.observed()).isTrue();
        assertThat(runtime.latestRun().status()).isEqualTo("SUCCEEDED");
        assertThat(runtime.latestRun().automaticDeletionEnabled()).isFalse();
        assertThat(runtime.latestRun().noticesPrepared()).isEqualTo(1);
    }

    @Test
    void exposesOnlyTypedAnonymousHistoryAndRejectsUnknownMetadata() {
        var audits = service.deletionAudits("inactivity", "2026-08-28T00:00:00Z",
                "2026-08-30T00:00:00Z", 0, 20);
        assertThat(audits.total()).isOne();
        assertThat(audits.rows().getFirst().deleteCounts()).containsEntry("app_user", 1);
        assertThat(audits.rows().getFirst().countsReadable()).isTrue();
        assertThat(audits.rows().getFirst().toString()).doesNotContain("subject-digest");

        var events = service.lifecycleEvents("deletion_completed", "inactivity", "", "", 0, 20);
        assertThat(events.total()).isEqualTo(2);
        var safe = events.rows().stream().filter(row -> "job-safe".equals(row.referenceId())).findFirst().orElseThrow();
        assertThat(safe.attemptCount()).isEqualTo(1);
        assertThat(safe.deleteCounts()).containsEntry("app_user", 1);
        assertThat(safe.metadataReadable()).isTrue();
        var rejected = events.rows().stream().filter(row -> "job-unknown".equals(row.referenceId()))
                .findFirst().orElseThrow();
        assertThat(rejected.metadataReadable()).isFalse();
        assertThat(rejected.toString()).doesNotContain("private@example.invalid", "email");
    }

    @Test
    void rejectsUnknownCodesInvalidRangesAndUnboundedPagesBeforeQuerying() {
        assertThatThrownBy(() -> service.notices("NOT_A_STATUS", 0, 20))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.lifecycleEvents("", "", "2026-08-30T00:00:00Z",
                "2026-08-29T00:00:00Z", 0, 20)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.deletionJobs("", "", 0, 101))
                .isInstanceOf(ResponseStatusException.class);
    }

    private void createSchema() {
        jdbc.execute("""
                create table account_retention_state(
                    user_id varchar(64),policy_state varchar(24),scheduled_deletion_at timestamp,updated_at timestamp)
                """);
        jdbc.execute("""
                create table account_deletion_notice(
                    id varchar(80),user_id varchar(64),subject_digest varchar(128),status varchar(24),
                    scheduled_deletion_at timestamp,attempt_count integer,next_attempt_at timestamp,
                    last_error_code varchar(80),updated_at timestamp)
                """);
        jdbc.execute("""
                create table account_deletion_job(
                    id varchar(80),user_id varchar(64),subject_digest varchar(128),reason varchar(32),status varchar(24),
                    attempt_count integer,last_error_code varchar(80),requested_at timestamp,updated_at timestamp,
                    completed_at timestamp)
                """);
        jdbc.execute("""
                create table account_deletion_audit(
                    id varchar(80),subject_digest varchar(128),reason varchar(32),delete_counts_json clob,
                    completed_at timestamp)
                """);
        jdbc.execute("""
                create table account_lifecycle_event(
                    id varchar(80),event_type varchar(48),reason varchar(32),reference_id varchar(80),
                    error_code varchar(80),metadata_json clob,occurred_at timestamp)
                """);
        jdbc.execute("""
                create table account_retention_run(
                    id varchar(80),trigger_source varchar(24),status varchar(24),
                    policy_evaluation_enabled boolean,notification_delivery_enabled boolean,
                    automatic_deletion_enabled boolean,mail_gateway_ready boolean,notice_after_days integer,
                    delete_after_days integer,minimum_notice_days integer,batch_size integer,
                    notices_prepared integer,notices_delivered integer,deletion_jobs_prepared integer,
                    error_code varchar(80),started_at timestamp,finished_at timestamp)
                """);
        jdbc.execute("create table ai_usage_log(id varchar(80),attribution_status varchar(24))");
    }

    private void insertFacts() {
        jdbc.update("insert into account_retention_state values ('active-user','ACTIVE',null,?)", at(NOW));
        jdbc.update("insert into account_retention_state values ('current-user','NOTICE_SENT',?,?)",
                at(NOW.plusSeconds(12 * 3600L)), at(NOW));
        jdbc.update("insert into account_deletion_notice values (?,?,?,'SENT',?,1,null,null,?)",
                "notice-1", "current-user", "subject-digest", at(NOW.plusSeconds(12 * 3600L)), at(NOW));
        jdbc.update("insert into account_deletion_job values (?,?,?,'INACTIVITY','DELETE_DUE',1,null,?,?,null)",
                "job-1", "current-user", "subject-digest", at(NOW.minusSeconds(3600)), at(NOW));
        jdbc.update("insert into account_deletion_audit values (?,?,?,'{\"app_user\":1}',?)",
                "audit-1", "subject-digest", "INACTIVITY", at(NOW));
        jdbc.update("insert into account_lifecycle_event values (?,?,?,'job-safe',null,?,?)",
                "event-safe", "DELETION_COMPLETED", "INACTIVITY",
                "{\"attemptCount\":1,\"deleteCounts\":{\"app_user\":1}}", at(NOW));
        jdbc.update("insert into account_lifecycle_event values (?,?,?,'job-unknown',null,?,?)",
                "event-unknown", "DELETION_COMPLETED", "INACTIVITY",
                "{\"email\":\"private@example.invalid\"}", at(NOW.minusSeconds(1)));
        jdbc.update("""
                insert into account_retention_run values (
                    'run-1','SCHEDULED','SUCCEEDED',true,true,false,true,23,30,7,50,1,1,1,null,?,?)
                """, at(NOW.minusSeconds(60)), at(NOW.minusSeconds(55)));
        jdbc.update("insert into ai_usage_log values ('usage-unresolved','UNRESOLVED')");
    }

    private Timestamp at(Instant instant) {
        return Timestamp.from(instant);
    }
}

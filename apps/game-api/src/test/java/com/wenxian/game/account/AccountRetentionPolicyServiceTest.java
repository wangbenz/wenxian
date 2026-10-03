package com.wenxian.game.account;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountRetentionPolicyServiceTest {
    @Test
    void preparesNoticeDeliversItAndOnlyThenCreatesDueDeletionJob() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            Instant activityAt = Instant.parse("2026-08-01T00:00:00Z");
            Instant evaluationAt = Instant.parse("2026-08-24T00:00:00Z");
            insertAccountAndActivity(jdbc, "notice-user", activityAt);
            CapturingGateway gateway = new CapturingGateway(false);
            AccountRetentionPolicyService service = new AccountRetentionPolicyService(jdbc, gateway);

            assertThat(service.prepareNotices(evaluationAt, 10)).isOne();
            assertThat(jdbc.queryForObject("select status from account_deletion_notice", String.class))
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select policy_state from account_retention_state", String.class))
                    .isEqualTo("NOTICE_DUE");

            assertThat(service.dispatchNotices(evaluationAt, 10)).isOne();
            assertThat(gateway.messages).hasSize(1);
            assertThat(gateway.messages.getFirst().plainText())
                    .contains("自动取消本次计划")
                    .doesNotContain("notice-user");
            assertThat(jdbc.queryForObject("select policy_state from account_retention_state", String.class))
                    .isEqualTo("NOTICE_SENT");

            assertThat(service.prepareDeletionJobs(evaluationAt.plusSeconds(7 * 86400L - 1), 10)).isZero();
            assertThat(service.prepareDeletionJobs(evaluationAt.plusSeconds(7 * 86400L), 10)).isOne();
            assertThat(jdbc.queryForObject("select status from account_deletion_job", String.class))
                    .isEqualTo("DELETE_DUE");
            assertThat(jdbc.queryForObject("select policy_state from account_retention_state", String.class))
                    .isEqualTo("DELETE_DUE");
            assertThat(jdbc.queryForList(
                    "select event_type from account_lifecycle_event", String.class))
                    .containsExactlyInAnyOrder("NOTICE_PREPARED", "NOTICE_SENT", "DELETE_DUE");
            assertThat(jdbc.queryForList(
                    "select metadata_json from account_lifecycle_event", String.class))
                    .allSatisfy(metadata -> assertThat(metadata)
                            .doesNotContain("notice-user", "@test.invalid"));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void resumedActivityCancelsPendingNoticeBeforeDelivery() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            Instant activityAt = Instant.parse("2026-08-01T00:00:00Z");
            Instant evaluationAt = Instant.parse("2026-08-24T00:00:00Z");
            insertAccountAndActivity(jdbc, "resumed-user", activityAt);
            CapturingGateway gateway = new CapturingGateway(false);
            AccountRetentionPolicyService service = new AccountRetentionPolicyService(jdbc, gateway);
            assertThat(service.prepareNotices(evaluationAt, 10)).isOne();

            new AccountActivityService(jdbc).record("resumed-user", AccountActivitySource.LOGIN,
                    evaluationAt.plusSeconds(60));

            assertThat(service.dispatchNotices(evaluationAt.plusSeconds(120), 10)).isZero();
            assertThat(gateway.messages).isEmpty();
            assertThat(jdbc.queryForObject("select status from account_deletion_notice", String.class))
                    .isEqualTo("CANCELED");
            assertThat(jdbc.queryForObject("select policy_state from account_retention_state", String.class))
                    .isEqualTo("ACTIVE");
        } finally {
            database.shutdown();
        }
    }

    @Test
    void failedDeliveryBecomesRetryableWithoutAdvancingRetentionState() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            Instant activityAt = Instant.parse("2026-08-01T00:00:00Z");
            Instant evaluationAt = Instant.parse("2026-08-24T00:00:00Z");
            insertAccountAndActivity(jdbc, "retry-user", activityAt);
            AccountRetentionPolicyService service = new AccountRetentionPolicyService(jdbc,
                    new CapturingGateway(true));
            assertThat(service.prepareNotices(evaluationAt, 10)).isOne();

            assertThat(service.dispatchNotices(evaluationAt, 10)).isZero();

            var notice = jdbc.queryForMap("select status,attempt_count,last_error_code,next_attempt_at "
                    + "from account_deletion_notice");
            assertThat(notice.get("STATUS")).isEqualTo("RETRYABLE_FAILED");
            assertThat(notice.get("ATTEMPT_COUNT")).isEqualTo(1);
            assertThat(notice.get("LAST_ERROR_CODE")).isEqualTo("ILLEGALSTATEEXCEPTION");
            assertThat(notice.get("NEXT_ATTEMPT_AT")).isNotNull();
            assertThat(jdbc.queryForObject("select policy_state from account_retention_state", String.class))
                    .isEqualTo("NOTICE_DUE");
            assertThat(jdbc.queryForObject("select count(*) from account_deletion_job", Integer.class)).isZero();
            var failedEvent = jdbc.queryForMap("""
                    select event_type,error_code,metadata_json from account_lifecycle_event
                    where event_type='NOTICE_RETRYABLE_FAILED'
                    """);
            assertThat(failedEvent.get("ERROR_CODE")).isEqualTo("ILLEGALSTATEEXCEPTION");
            assertThat(failedEvent.get("METADATA_JSON").toString())
                    .contains("attemptCount").doesNotContain("retry-user", "@test.invalid", "simulated");
        } finally {
            database.shutdown();
        }
    }

    @Test
    void expiredSendingLeaseCanBeReclaimedAfterProcessInterruption() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            Instant activityAt = Instant.parse("2026-08-01T00:00:00Z");
            Instant evaluationAt = Instant.parse("2026-08-24T00:00:00Z");
            insertAccountAndActivity(jdbc, "lease-user", activityAt);
            CapturingGateway gateway = new CapturingGateway(false);
            AccountRetentionPolicyService service = new AccountRetentionPolicyService(jdbc, gateway);
            assertThat(service.prepareNotices(evaluationAt, 10)).isOne();
            jdbc.update("""
                    update account_deletion_notice
                    set status='SENDING',lease_owner='dead-process',lease_expires_at=?
                    """, Timestamp.from(evaluationAt.minusSeconds(1)));

            assertThat(service.dispatchNotices(evaluationAt, 10)).isOne();
            assertThat(gateway.messages).hasSize(1);
            assertThat(jdbc.queryForObject("select status from account_deletion_notice", String.class))
                    .isEqualTo("SENT");
            assertThat(jdbc.queryForObject("select attempt_count from account_deletion_notice", Integer.class))
                    .isEqualTo(1);
        } finally {
            database.shutdown();
        }
    }

    private EmbeddedDatabase database() {
        return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
    }

    private void insertAccountAndActivity(JdbcTemplate jdbc, String userId, Instant activityAt) {
        Timestamp timestamp = Timestamp.from(activityAt);
        jdbc.update("""
                insert into app_user(id,email,status,registered_at,last_login_at)
                values (?,?,'ACTIVE',?,?)
                """, userId, userId + "@test.invalid", timestamp, timestamp);
        jdbc.update("""
                insert into account_retention_state(
                    user_id,last_meaningful_activity_at,activity_source,policy_state,activity_origin,version,updated_at
                ) values (?,?,'LEGACY_BACKFILL','ACTIVE','BACKFILL',0,?)
                """, userId, timestamp, timestamp);
    }

    private static final class CapturingGateway implements AccountDeletionNoticeGateway {
        private final boolean fail;
        private final List<NoticeMessage> messages = new ArrayList<>();

        private CapturingGateway(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void send(NoticeMessage message) {
            messages.add(message);
            if (fail) throw new IllegalStateException("simulated provider failure");
        }
    }
}

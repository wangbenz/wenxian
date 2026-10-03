package com.wenxian.game.account;

import com.wenxian.game.api.GameApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountDeletionServiceTest {
    @Test
    void physicallyDeletesCurrentV014LegacyCreditAndPersonalLogsButKeepsAnonymousAudit() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            installLegacyTables(jdbc);
            insertAccount(jdbc, "delete-user", "delete@test.invalid");
            insertStory(jdbc, "story-delete", "delete-user", null);
            Timestamp now = Timestamp.from(Instant.parse("2026-08-29T00:00:00Z"));
            jdbc.update("insert into v014_game_save(id,user_id) values ('v014-delete','delete-user')");
            jdbc.update("insert into game_save(id,user_id) values ('game-delete','delete-user')");
            jdbc.update("insert into game_turn(id,save_id) values ('game-turn-delete','game-delete')");
            jdbc.update("insert into game_life_archive(id,user_id) values ('life-delete','delete-user')");
            jdbc.update("insert into game_life_turn_archive(id,life_archive_id) values ('life-turn-delete','life-delete')");
            jdbc.update("""
                    insert into analytics_event(id,event_key,event_type,user_id,release_version,engine_code,
                        data_origin,occurred_at,recorded_at)
                    values ('event-delete','event:delete','SAVE_CREATED','delete-user','TEST','LIGHT_NARRATIVE',
                        'REALTIME',?,?)
                    """, now, now);
            jdbc.update("""
                    insert into ai_usage_log(
                        id,user_id,attribution_status,save_id,operation,credential_source,model,status,created_at
                    ) values ('usage-user','delete-user','ACCOUNT_LINKED',null,'MODEL_TASK','PUBLIC','test','SUCCESS',?),
                             ('usage-save',null,'ACCOUNT_LINKED','v014-delete','TURN','PUBLIC','test','SUCCESS',?),
                             ('usage-anonymous',null,'ANONYMOUS_LEGACY',null,'V014_CHARACTER_ROUTES','PUBLIC','test','SUCCESS',?)
                    """, now, now, now);
            jdbc.update("""
                    insert into operation_error(reference_id,save_id,subject_user_id,operation,category,error_code,
                        http_status,public_message,diagnostic,created_at)
                    values ('error-delete',null,'delete-user','POST /test','SYSTEM','TEST',500,'test',
                        'delete@test.invalid',?)
                    """, now);
            jdbc.update("""
                    insert into admin_operation_log(id,operated_at,operation,detail,subject_user_id)
                    values ('admin-delete',?,'RESET_PLAYER_PASSWORD','delete@test.invalid','delete-user')
                    """, now);
            jdbc.update("""
                    insert into turn_credit_grant(
                        id,user_id,credit_class,grant_type,idempotency_key,granted_count,available_count,
                        reserved_count,consumed_count,expired_count,revoked_count,status,reason,created_by,
                        created_at,updated_at
                    ) values ('grant-delete','delete-user','SPONSORED','INITIAL','grant:delete',1,0,0,1,0,0,
                        'EXHAUSTED','test','SYSTEM',?,?)
                    """, now, now);
            jdbc.update("""
                    insert into turn_credit_ledger(
                        id,user_id,grant_id,reservation_id,event_type,event_key,available_delta,reserved_delta,
                        consumed_delta,balance_after,operator_id,remark,created_at
                    ) values ('ledger-delete','delete-user','grant-delete',null,'CONSUME','ledger:delete',0,0,1,0,
                        'SYSTEM','test',?)
                    """, now);

            AccountDeletionService.DeleteResult result = new AccountDeletionService(jdbc)
                    .deleteUserRequested("delete-user");

            assertThat(result.deletedRows()).containsEntry("app_user", 1)
                    .containsEntry("story_save", 1)
                    .containsEntry("v014_game_save", 1)
                    .containsEntry("game_save", 1)
                    .containsEntry("game_life_archive", 1)
                    .containsEntry("turn_credit_ledger", 1)
                    .containsEntry("turn_credit_grant", 1);
            assertThat(jdbc.queryForObject("select count(*) from app_user where id='delete-user'", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("select count(*) from ai_usage_log", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("select attribution_status from ai_usage_log", String.class))
                    .isEqualTo("ANONYMOUS_LEGACY");
            assertThat(jdbc.queryForObject("select count(*) from operation_error", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from admin_operation_log", Integer.class)).isZero();
            var job = jdbc.queryForMap("select user_id,status,delete_counts_json from account_deletion_job");
            assertThat(job.get("USER_ID")).isNull();
            assertThat(job.get("STATUS")).isEqualTo("DELETED");
            assertThat(job.get("DELETE_COUNTS_JSON").toString()).doesNotContain("delete-user", "delete@test.invalid");
            var audit = jdbc.queryForMap("select subject_digest,delete_counts_json from account_deletion_audit");
            assertThat(audit.get("SUBJECT_DIGEST").toString()).doesNotContain("delete-user");
            assertThat(audit.get("DELETE_COUNTS_JSON").toString()).doesNotContain("delete-user", "delete@test.invalid");
            assertThat(jdbc.queryForList(
                    "select event_type from account_lifecycle_event", String.class))
                    .containsExactlyInAnyOrder("DELETE_DUE", "DELETION_STARTED", "DELETION_COMPLETED");
            assertThat(jdbc.queryForObject("select count(*) from account_lifecycle_event", Integer.class))
                    .isEqualTo(3);
            assertThat(jdbc.queryForList(
                    "select metadata_json from account_lifecycle_event", String.class))
                    .allSatisfy(metadata -> assertThat(metadata)
                            .doesNotContain("delete-user", "delete@test.invalid"));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void unattributedModelUsageFailsClosedAndLeavesAccountAndRetryableJob() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            insertAccount(jdbc, "blocked-user", "blocked@test.invalid");
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("""
                    insert into ai_usage_log(
                        id,user_id,attribution_status,save_id,operation,credential_source,model,status,created_at
                    ) values ('usage-unattributed',null,'UNRESOLVED',null,'TURN','PUBLIC','test','FAILED',?)
                    """, now);

            assertThatThrownBy(() -> new AccountDeletionService(jdbc).deleteUserRequested("blocked-user"))
                    .isInstanceOf(GameApiException.class)
                    .extracting(error -> ((GameApiException) error).code())
                    .isEqualTo("ACCOUNT_DELETION_BLOCKED");
            assertThat(jdbc.queryForObject("select status from app_user where id='blocked-user'", String.class))
                    .isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("select status from account_deletion_job", String.class))
                    .isEqualTo("RETRYABLE_FAILED");
            assertThat(jdbc.queryForObject("select attempt_count from account_deletion_job", Integer.class))
                    .isOne();
            var failureEvent = jdbc.queryForMap("""
                    select event_type,error_code,metadata_json from account_lifecycle_event
                    where event_type='DELETION_RETRYABLE_FAILED'
                    """);
            assertThat(failureEvent.get("ERROR_CODE")).isEqualTo("ACCOUNT_DELETION_BLOCKED");
            assertThat(failureEvent.get("METADATA_JSON").toString())
                    .contains("attemptCount").doesNotContain("blocked-user", "blocked@test.invalid");
            assertThat(jdbc.queryForList(
                    "select event_type from account_lifecycle_event", String.class))
                    .containsExactlyInAnyOrder("DELETE_DUE", "DELETION_STARTED", "DELETION_RETRYABLE_FAILED");
        } finally {
            database.shutdown();
        }
    }

    @Test
    void activeCreditReservationAndCrossAccountParentEachBlockDeletion() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            insertAccount(jdbc, "credit-user", "credit@test.invalid");
            insertAccount(jdbc, "parent-user", "parent@test.invalid");
            insertAccount(jdbc, "child-user", "child@test.invalid");
            insertStory(jdbc, "parent-story", "parent-user", null);
            insertStory(jdbc, "child-story", "child-user", "parent-story");
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("""
                    insert into turn_credit_grant(
                        id,user_id,credit_class,grant_type,idempotency_key,granted_count,available_count,
                        reserved_count,consumed_count,expired_count,revoked_count,status,reason,created_by,
                        created_at,updated_at
                    ) values ('grant-active','credit-user','SPONSORED','INITIAL','grant:active',1,0,1,0,0,0,
                        'ACTIVE','test','SYSTEM',?,?)
                    """, now, now);
            jdbc.update("""
                    insert into turn_credit_reservation(
                        id,user_id,save_id,request_id,grant_id,amount,status,expires_at,created_at,updated_at
                    ) values ('reservation-active','credit-user','none','request','grant-active',1,'RESERVED',?,?,?)
                    """, now, now, now);

            assertThatThrownBy(() -> new AccountDeletionService(jdbc).deleteUserRequested("credit-user"))
                    .isInstanceOf(GameApiException.class);
            assertThatThrownBy(() -> new AccountDeletionService(jdbc).deleteUserRequested("parent-user"))
                    .isInstanceOf(GameApiException.class);
            assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(3);
        } finally {
            database.shutdown();
        }
    }

    @Test
    void scheduledDeletionCannotRunWhileFeatureFlagIsOff() {
        EmbeddedDatabase database = database();
        try {
            assertThatThrownBy(() -> new AccountDeletionService(new JdbcTemplate(database))
                    .deleteScheduled("job-disabled"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("自动删除开关未开启");
        } finally {
            database.shutdown();
        }
    }

    private EmbeddedDatabase database() {
        return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
    }

    private void insertAccount(JdbcTemplate jdbc, String userId, String email) {
        Timestamp now = Timestamp.from(Instant.parse("2026-08-01T00:00:00Z"));
        jdbc.update("""
                insert into app_user(id,email,status,registered_at,last_login_at) values (?,?, 'ACTIVE',?,?)
                """, userId, email, now, now);
        jdbc.update("""
                insert into account_retention_state(
                    user_id,last_meaningful_activity_at,activity_source,policy_state,activity_origin,version,updated_at
                ) values (?,?,'LOGIN','ACTIVE','REALTIME',0,?)
                """, userId, now, now);
    }

    private void insertStory(JdbcTemplate jdbc, String saveId, String userId, String parentSaveId) {
        Timestamp now = Timestamp.from(Instant.parse("2026-08-01T00:00:00Z"));
        jdbc.update("""
                insert into story_save(
                    id,user_id,slot_no,active_slot_no,status,world_code,engine_code,engine_version,
                    state_schema_version,source_type,parent_save_id,state_json,created_release,last_played_release,
                    created_at,updated_at
                ) values (?,?,1,1,'ACTIVE','world','LIGHT_NARRATIVE',1,1,'NATIVE',?,'{}','TEST','TEST',?,?)
                """, saveId, userId, parentSaveId, now, now);
    }

    private void installLegacyTables(JdbcTemplate jdbc) {
        jdbc.execute("create table v014_game_save(id varchar(80) primary key,user_id varchar(64) not null)");
        jdbc.execute("create table game_save(id varchar(80) primary key,user_id varchar(64) not null)");
        jdbc.execute("create table game_turn(id varchar(80) primary key,save_id varchar(80) not null)");
        jdbc.execute("create table game_life_archive(id varchar(80) primary key,user_id varchar(64) not null)");
        jdbc.execute("create table game_life_turn_archive(id varchar(80) primary key,life_archive_id varchar(80) not null)");
        jdbc.execute("""
                create table admin_operation_log(
                    id varchar(64) primary key,operated_at timestamp not null,operation varchar(64) not null,
                    detail clob not null,subject_user_id varchar(64)
                )
                """);
    }
}

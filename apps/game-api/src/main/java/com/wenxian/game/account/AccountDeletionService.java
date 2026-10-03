package com.wenxian.game.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.auth.TokenHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 单账号物理删除的统一事务边界，供玩家主动注销和未来30天到期任务共用。
 *
 * <p>服务会先校验运行库结构、无法归属的历史模型日志、在途点数和跨账号父命卷。任何门禁不通过时
 * 整个账号删除事务回滚，并留下可重试任务；它不会猜测缺失字段、静默跳过已存在但结构异常的表，
 * 也不会把“删除了 app_user”冒充完整删除。历史表不存在可以跳过，存在时则按已知外键顺序清理。</p>
 */
@Service
public class AccountDeletionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final AccountLifecycleEventRepository events;
    private final boolean automaticDeletionEnabled;
    private final String subjectHashPepper;

    @Autowired
    public AccountDeletionService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                  ObjectMapper mapper,
                                  AccountLifecycleEventRepository events,
                                  @Value("${game.account-retention.automatic-deletion-enabled:false}")
                                  boolean automaticDeletionEnabled,
                                  @Value("${game.account-retention.subject-hash-pepper:wenxian-local-retention-pepper-change-me}")
                                  String subjectHashPepper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.mapper = mapper;
        this.events = events;
        this.automaticDeletionEnabled = automaticDeletionEnabled;
        this.subjectHashPepper = subjectHashPepper == null ? "" : subjectHashPepper;
    }

    /** 仅供内嵌数据库测试和兼容构造器使用；自动删除固定关闭。 */
    public AccountDeletionService(JdbcTemplate jdbc) {
        this(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), new ObjectMapper(),
                new AccountLifecycleEventRepository(jdbc, new ObjectMapper()), false,
                "test-retention-pepper");
    }

    public DeleteResult deleteUserRequested(String userId) {
        return execute(userId, "USER_REQUEST", null);
    }

    public DeleteResult deleteScheduled(String jobId) {
        if (!automaticDeletionEnabled) {
            throw new IllegalStateException("账号自动删除开关未开启");
        }
        ScheduledSubject subject = jdbc.query("""
                        select j.user_id
                        from account_deletion_job j
                        join account_retention_state r on r.user_id=j.user_id
                        where j.id=? and j.status='DELETE_DUE' and r.policy_state='DELETE_DUE'
                          and r.scheduled_deletion_at<=? and r.last_meaningful_activity_at<=j.expected_activity_before
                        """, (row, index) -> new ScheduledSubject(row.getString(1)),
                jobId, Timestamp.from(Instant.now())).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("删除任务尚未到期或账号已经恢复活动"));
        return execute(subject.userId(), "INACTIVITY", jobId);
    }

    private DeleteResult execute(String userId, String reason, String existingJobId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("账号ID不能为空");
        SchemaSnapshot schema = SchemaSnapshot.load(jdbc);
        requireCoreSchema(schema);
        String jobId = existingJobId == null ? createOrReuseJob(userId, reason) : existingJobId;
        try {
            DeleteResult result = transactions.execute(status -> deleteInTransaction(schema, jobId, userId, reason));
            if (result == null) throw new IllegalStateException("账号删除事务没有返回结果");
            return result;
        } catch (RuntimeException failure) {
            markRetryableFailure(jobId, stableErrorCode(failure));
            throw failure;
        }
    }

    private DeleteResult deleteInTransaction(SchemaSnapshot schema, String jobId, String userId, String reason) {
        AccountSubject subject = jdbc.query("select id,email,status from app_user where id=? for update",
                (row, index) -> new AccountSubject(row.getString(1), row.getString(2), row.getString(3)), userId)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("待删除账号不存在"));
        if (!"ACTIVE".equals(subject.status()) && !"DELETING".equals(subject.status())) {
            throw blocked("账号当前状态不允许注销");
        }
        assertDeletionGuards(schema, userId);
        jdbc.update("""
                update account_deletion_job set status='DELETING',attempt_count=attempt_count+1,
                    last_error_code=null,updated_at=? where id=? and user_id=?
                """, Timestamp.from(Instant.now()), jobId, userId);
        Integer attemptCount = jdbc.queryForObject(
                "select attempt_count from account_deletion_job where id=?", Integer.class, jobId);
        events.deletionStarted(jobId, reason, attemptCount == null ? 0 : attemptCount, Instant.now());
        jdbc.update("update app_user set status='DELETING' where id=?", userId);

        Map<String, Integer> counts = new LinkedHashMap<>();
        deleteUnkeyedLogs(schema, counts, subject);
        deleteStoryMappingsAndLogs(schema, counts, userId);
        deleteCurrentStory(schema, counts, userId);
        deleteV014Story(schema, counts, userId);
        deleteLegacyStory(schema, counts, userId);
        deleteCredits(schema, counts, userId);
        deletePreferencesAndCredentials(schema, counts, userId);
        deleteAccountFacts(schema, counts, subject);

        int accountRows = jdbc.update("delete from app_user where id=?", userId);
        add(counts, "app_user", accountRows);
        if (accountRows != 1) throw new IllegalStateException("账号主记录删除行数不是1");

        Instant completedAt = Instant.now();
        String digest = subjectDigest(userId);
        String countsJson = json(counts);
        int completed = jdbc.update("""
                update account_deletion_job
                set user_id=null,status='DELETED',lease_owner=null,lease_expires_at=null,last_error_code=null,
                    delete_counts_json=?,updated_at=?,completed_at=? where id=? and status='DELETING'
                """, countsJson, Timestamp.from(completedAt), Timestamp.from(completedAt), jobId);
        if (completed != 1) throw new IllegalStateException("删除任务完成状态写入失败");
        jdbc.update("""
                insert into account_deletion_audit(id,subject_digest,reason,delete_counts_json,completed_at)
                values (?,?,?,?,?)
                """, "deletion-audit-" + UUID.randomUUID(), digest, reason, countsJson,
                Timestamp.from(completedAt));
        events.deletionCompleted(jobId, reason, attemptCount == null ? 0 : attemptCount, counts, completedAt);
        return new DeleteResult(jobId, counts);
    }

    private void assertDeletionGuards(SchemaSnapshot schema, String userId) {
        Integer unattributed = jdbc.queryForObject("""
                select count(*) from ai_usage_log where attribution_status='UNRESOLVED'
                """, Integer.class);
        if (unattributed != null && unattributed > 0) {
            throw blocked("模型调用仍有尚未完成归属治理的记录，暂不能确认完整删除");
        }
        if (schema.has("turn_credit_reservation")) {
            schema.require("turn_credit_reservation", "user_id", "status");
            Integer inFlight = jdbc.queryForObject("""
                    select count(*) from turn_credit_reservation where user_id=? and status='RESERVED'
                    """, Integer.class, userId);
            if (inFlight != null && inFlight > 0) throw blocked("账号仍有正在处理的推演点预占");
        }
        if (schema.has("story_save")) {
            Integer crossAccountChildren = jdbc.queryForObject("""
                    select count(*) from story_save child join story_save parent on parent.id=child.parent_save_id
                    where parent.user_id=? and child.user_id<>?
                    """, Integer.class, userId, userId);
            if (crossAccountChildren != null && crossAccountChildren > 0) {
                throw blocked("账号命卷仍被其他账号的命卷引用");
            }
        }
    }

    private void deleteUnkeyedLogs(SchemaSnapshot schema, Map<String, Integer> counts, AccountSubject subject) {
        if (schema.has("admin_operation_log")) {
            schema.require("admin_operation_log", "subject_user_id", "detail");
            add(counts, "admin_operation_log", jdbc.update("""
                    delete from admin_operation_log
                    where subject_user_id=? or instr(lower(detail),lower(?))>0 or instr(detail,?)>0
                    """, subject.userId(), subject.email(), subject.userId()));
        }
        schema.require("operation_error", "subject_user_id", "diagnostic");
        add(counts, "operation_error", jdbc.update("""
                delete from operation_error
                where subject_user_id=? or instr(lower(diagnostic),lower(?))>0 or instr(diagnostic,?)>0
                """, subject.userId(), subject.email(), subject.userId()));
        add(counts, "ai_usage_log", jdbc.update("delete from ai_usage_log where user_id=?", subject.userId()));
    }

    private void deleteStoryMappingsAndLogs(SchemaSnapshot schema, Map<String, Integer> counts, String userId) {
        for (String saveTable : List.of("story_save", "v014_game_save", "game_save")) {
            if (!schema.has(saveTable)) continue;
            schema.require(saveTable, "id", "user_id");
            deleteBySaveSubquery(schema, counts, "agent_execution", "save_id", saveTable, userId);
            deleteBySaveSubquery(schema, counts, "operation_error", "save_id", saveTable, userId);
            deleteBySaveSubquery(schema, counts, "ai_usage_log", "save_id", saveTable, userId);
        }
        if (schema.has("story_save_migration")) {
            schema.require("story_save_migration", "source_save_id", "target_save_id");
            if (schema.has("story_save")) add(counts, "story_save_migration", jdbc.update("""
                    delete from story_save_migration
                    where target_save_id in (select id from story_save where user_id=?)
                    """, userId));
            for (String sourceTable : List.of("v014_game_save", "game_save")) {
                if (!schema.has(sourceTable)) continue;
                add(counts, "story_save_migration", jdbc.update("delete from story_save_migration "
                        + "where source_save_id in (select id from " + sourceTable + " where user_id=?)", userId));
            }
        }
    }

    private void deleteCurrentStory(SchemaSnapshot schema, Map<String, Integer> counts, String userId) {
        if (!schema.has("story_save")) return;
        deleteBySaveSubquery(schema, counts, "story_entity_alias", "save_id", "story_save", userId);
        for (String table : List.of("story_turn_diagnostic", "story_turn", "story_memory", "story_conversation",
                "story_state_correction", "story_entity")) {
            deleteBySaveSubquery(schema, counts, table, "save_id", "story_save", userId);
        }
        jdbc.update("update story_save set parent_save_id=null where user_id=? and parent_save_id is not null", userId);
        add(counts, "story_save", jdbc.update("delete from story_save where user_id=?", userId));
    }

    private void deleteV014Story(SchemaSnapshot schema, Map<String, Integer> counts, String userId) {
        if (!schema.has("v014_game_save")) return;
        for (String table : List.of("v014_game_turn", "v014_conversation", "v014_state_correction")) {
            deleteBySaveSubquery(schema, counts, table, "save_id", "v014_game_save", userId);
        }
        add(counts, "v014_game_save", jdbc.update("delete from v014_game_save where user_id=?", userId));
    }

    private void deleteLegacyStory(SchemaSnapshot schema, Map<String, Integer> counts, String userId) {
        if (schema.has("game_save")) {
            if (schema.has("agent_message") && schema.has("agent_conversation")) {
                schema.require("agent_message", "conversation_id");
                schema.require("agent_conversation", "id", "save_id");
                add(counts, "agent_message", jdbc.update("""
                        delete from agent_message where conversation_id in (
                            select id from agent_conversation where save_id in (
                                select id from game_save where user_id=?
                            )
                        )
                        """, userId));
            }
            for (String table : List.of("game_turn", "game_turn_request", "agent_conversation")) {
                deleteBySaveSubquery(schema, counts, table, "save_id", "game_save", userId);
            }
            add(counts, "game_save", jdbc.update("delete from game_save where user_id=?", userId));
        }
        if (schema.has("game_life_archive")) {
            schema.require("game_life_archive", "id", "user_id");
            if (schema.has("game_life_turn_archive")) {
                schema.require("game_life_turn_archive", "life_archive_id");
                add(counts, "game_life_turn_archive", jdbc.update("""
                        delete from game_life_turn_archive where life_archive_id in (
                            select id from game_life_archive where user_id=?
                        )
                        """, userId));
            }
            add(counts, "game_life_archive", jdbc.update(
                    "delete from game_life_archive where user_id=?", userId));
        }
    }

    private void deleteCredits(SchemaSnapshot schema, Map<String, Integer> counts, String userId) {
        if (schema.has("turn_credit_ledger")) {
            schema.require("turn_credit_ledger", "user_id");
            add(counts, "turn_credit_ledger", jdbc.update("delete from turn_credit_ledger where user_id=?", userId));
        }
        if (schema.has("turn_credit_reservation_allocation")) {
            schema.require("turn_credit_reservation_allocation", "reservation_id", "grant_id");
            add(counts, "turn_credit_reservation_allocation", jdbc.update("""
                    delete from turn_credit_reservation_allocation
                    where reservation_id in (select id from turn_credit_reservation where user_id=?)
                       or grant_id in (select id from turn_credit_grant where user_id=?)
                    """, userId, userId));
        }
        deleteByUser(schema, counts, "turn_credit_reservation", userId);
        deleteByUser(schema, counts, "turn_credit_grant", userId);
    }

    private void deletePreferencesAndCredentials(SchemaSnapshot schema, Map<String, Integer> counts, String userId) {
        for (String table : List.of("user_service_tier_preference", "user_model_preference",
                "v014_user_model_preference", "user_ai_provider_credential", "user_ai_credential")) {
            deleteByUser(schema, counts, table, userId);
        }
    }

    private void deleteAccountFacts(SchemaSnapshot schema, Map<String, Integer> counts, AccountSubject subject) {
        deleteByUser(schema, counts, "analytics_event", subject.userId());
        deleteByUser(schema, counts, "analytics_user_day", subject.userId());
        deleteByUser(schema, counts, "game_analytics_event", subject.userId());
        for (String table : List.of("user_release_receipt", "user_daily_quota", "game_access_whitelist",
                "user_session", "auth_registration", "account_deletion_notice", "account_retention_state")) {
            deleteByUser(schema, counts, table, subject.userId());
        }
        if (schema.has("auth_login_failure")) {
            schema.require("auth_login_failure", "email_hash");
            add(counts, "auth_login_failure", jdbc.update("delete from auth_login_failure where email_hash=?",
                    TokenHasher.sha256(subject.email().toLowerCase(Locale.ROOT))));
        }
    }

    private void deleteBySaveSubquery(SchemaSnapshot schema, Map<String, Integer> counts, String targetTable,
                                      String targetSaveColumn, String sourceSaveTable, String userId) {
        if (!schema.has(targetTable)) return;
        schema.require(targetTable, targetSaveColumn);
        add(counts, targetTable, jdbc.update("delete from " + targetTable + " where " + targetSaveColumn
                + " in (select id from " + sourceSaveTable + " where user_id=?)", userId));
    }

    private void deleteByUser(SchemaSnapshot schema, Map<String, Integer> counts, String table, String userId) {
        if (!schema.has(table)) return;
        schema.require(table, "user_id");
        add(counts, table, jdbc.update("delete from " + table + " where user_id=?", userId));
    }

    private String createOrReuseJob(String userId, String reason) {
        List<String> existing = jdbc.query("""
                select id from account_deletion_job
                where user_id=? and reason=? and status in ('DELETE_DUE','RETRYABLE_FAILED')
                order by requested_at limit 1
                """, (row, index) -> row.getString(1), userId, reason);
        if (!existing.isEmpty()) {
            String jobId = existing.getFirst();
            jdbc.update("""
                    update account_deletion_job set status='DELETE_DUE',last_error_code=null,updated_at=? where id=?
                    """, Timestamp.from(Instant.now()), jobId);
            events.deletionDue(jobId, reason, Instant.now());
            return jobId;
        }
        String jobId = "account-delete-" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into account_deletion_job(
                    id,user_id,subject_digest,reason,status,expected_activity_before,lease_owner,lease_expires_at,
                    attempt_count,last_error_code,delete_counts_json,requested_at,updated_at,completed_at
                ) values (?,?,?,?,'DELETE_DUE',null,null,null,0,null,'{}',?,?,null)
                """, jobId, userId, subjectDigest(userId), reason, Timestamp.from(now), Timestamp.from(now));
        events.deletionDue(jobId, reason, now);
        return jobId;
    }

    private void markRetryableFailure(String jobId, String errorCode) {
        Instant failedAt = Instant.now();
        int changed = jdbc.update("""
                update account_deletion_job
                set status='RETRYABLE_FAILED',attempt_count=attempt_count+1,
                    lease_owner=null,lease_expires_at=null,last_error_code=?,updated_at=?
                where id=? and status<>'DELETED'
                """, errorCode, Timestamp.from(failedAt), jobId);
        if (changed != 1) return;
        FailedJob job = jdbc.query("""
                        select reason,attempt_count from account_deletion_job where id=?
                        """, (row, index) -> new FailedJob(row.getString(1), row.getInt(2)), jobId)
                .stream().findFirst().orElse(null);
        if (job != null) {
            events.deletionStarted(jobId, job.reason(), job.attemptCount(), failedAt);
            events.deletionRetryableFailed(jobId, job.reason(), job.attemptCount(), errorCode, failedAt);
        }
    }

    private void requireCoreSchema(SchemaSnapshot schema) {
        AccountDataOwnershipCatalog.assertClassifiedSubjectTables(schema.columns());
        schema.require("app_user", "id", "email", "status");
        schema.require("account_retention_state", "user_id", "policy_state", "last_meaningful_activity_at");
        schema.require("account_deletion_job", "id", "user_id", "subject_digest", "status", "delete_counts_json");
        schema.require("account_deletion_audit", "id", "subject_digest", "delete_counts_json");
        schema.require("account_lifecycle_event", "event_key", "event_type", "reference_id", "metadata_json");
        schema.require("ai_usage_log", "user_id", "save_id", "attribution_status");
        schema.require("operation_error", "subject_user_id", "save_id", "diagnostic");
        schema.require("story_save", "id", "user_id", "parent_save_id");
    }

    private GameApiException blocked(String detail) {
        return GameApiException.of(HttpStatus.CONFLICT, "ACCOUNT_DELETION_BLOCKED", "ACCOUNT",
                "账号暂时不能注销", detail + "。账号和命卷均未删除。",
                List.of("稍后重试", "联系管理员核查删除门禁"));
    }

    private String subjectDigest(String userId) {
        return TokenHasher.sha256(subjectHashPepper + ":" + userId);
    }

    private String json(Map<String, Integer> counts) {
        try {
            return mapper.writeValueAsString(counts);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("删除计数无法序列化", error);
        }
    }

    private String stableErrorCode(RuntimeException failure) {
        if (failure instanceof GameApiException api) return api.code();
        return failure.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    private void add(Map<String, Integer> counts, String table, int changed) {
        counts.merge(table, changed, Integer::sum);
    }

    public record DeleteResult(String jobId, Map<String, Integer> deletedRows) {
        public DeleteResult {
            deletedRows = Map.copyOf(deletedRows);
        }
    }

    private record AccountSubject(String userId, String email, String status) { }
    private record ScheduledSubject(String userId) { }
    private record FailedJob(String reason, int attemptCount) { }

    private record SchemaSnapshot(Map<String, Set<String>> columns) {
        private static SchemaSnapshot load(JdbcTemplate jdbc) {
            return jdbc.execute((ConnectionCallback<SchemaSnapshot>) connection -> {
                DatabaseMetaData metadata = connection.getMetaData();
                Map<String, Set<String>> tables = new LinkedHashMap<>();
                List<String> physicalNames = new ArrayList<>();
                try (ResultSet result = metadata.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                    while (result.next()) physicalNames.add(result.getString("TABLE_NAME"));
                }
                for (String physicalName : physicalNames) {
                    Set<String> tableColumns = new LinkedHashSet<>();
                    try (ResultSet result = metadata.getColumns(connection.getCatalog(), null, physicalName, "%")) {
                        while (result.next()) {
                            tableColumns.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                        }
                    }
                    tables.put(physicalName.toLowerCase(Locale.ROOT), Set.copyOf(tableColumns));
                }
                return new SchemaSnapshot(Map.copyOf(tables));
            });
        }

        private boolean has(String table) {
            return columns.containsKey(table.toLowerCase(Locale.ROOT));
        }

        private void require(String table, String... requiredColumns) {
            Set<String> actual = columns.get(table.toLowerCase(Locale.ROOT));
            if (actual == null) throw new IllegalStateException("账号删除所需表缺失：" + table);
            for (String column : requiredColumns) {
                if (!actual.contains(column.toLowerCase(Locale.ROOT))) {
                    throw new IllegalStateException("账号删除所需字段缺失：" + table + "." + column);
                }
            }
        }
    }
}

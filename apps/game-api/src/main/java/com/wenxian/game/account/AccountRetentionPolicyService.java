package com.wenxian.game.account;

import com.wenxian.game.auth.TokenHasher;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 账号30天留存策略的候选、预通知和到期任务编排。
 *
 * <p>本服务只读取 {@code account_retention_state} 的权威活动时间，不从页面访问或会话保活推断活动。
 * 通知发送与数据库状态分两段提交，发送失败只形成可重试通知，不会提前创建删除任务；活动版本变化会
 * 使旧通知失效。生产评估和真实发送均由独立开关控制，默认关闭。</p>
 */
@Service
public class AccountRetentionPolicyService {
    private static final DateTimeFormatter NOTICE_TIME = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountDeletionNoticeGateway noticeGateway;
    private final AccountLifecycleEventRepository events;
    private final boolean policyEvaluationEnabled;
    private final boolean notificationDeliveryEnabled;
    private final Duration noticeAfter;
    private final Duration deleteAfter;
    private final Duration minimumNotice;
    private final Duration retryDelay;
    private final String subjectHashPepper;
    private final String leaseOwner = "retention-" + UUID.randomUUID();

    @Autowired
    public AccountRetentionPolicyService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectProvider<AccountDeletionNoticeGateway> gatewayProvider,
            AccountLifecycleEventRepository events,
            @Value("${game.account-retention.policy-evaluation-enabled:false}") boolean policyEvaluationEnabled,
            @Value("${game.account-retention.notification-delivery-enabled:false}") boolean notificationDeliveryEnabled,
            @Value("${game.account-retention.notice-after-days:23}") int noticeAfterDays,
            @Value("${game.account-retention.delete-after-days:30}") int deleteAfterDays,
            @Value("${game.account-retention.minimum-notice-days:7}") int minimumNoticeDays,
            @Value("${game.account-retention.notice-retry-hours:6}") int noticeRetryHours,
            @Value("${game.account-retention.subject-hash-pepper:wenxian-local-retention-pepper-change-me}")
            String subjectHashPepper) {
        this(jdbc, transactionManager, gatewayProvider.getIfAvailable(), events, policyEvaluationEnabled,
                notificationDeliveryEnabled, noticeAfterDays, deleteAfterDays, minimumNoticeDays,
                noticeRetryHours, subjectHashPepper);
    }

    AccountRetentionPolicyService(JdbcTemplate jdbc, AccountDeletionNoticeGateway gateway) {
        this(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), gateway,
                new AccountLifecycleEventRepository(jdbc, new com.fasterxml.jackson.databind.ObjectMapper()), true, true,
                23, 30, 7, 6, "test-retention-pepper");
    }

    private AccountRetentionPolicyService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                          AccountDeletionNoticeGateway noticeGateway,
                                          AccountLifecycleEventRepository events,
                                          boolean policyEvaluationEnabled, boolean notificationDeliveryEnabled,
                                          int noticeAfterDays, int deleteAfterDays, int minimumNoticeDays,
                                          int noticeRetryHours, String subjectHashPepper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.noticeGateway = noticeGateway;
        this.events = events;
        this.policyEvaluationEnabled = policyEvaluationEnabled;
        this.notificationDeliveryEnabled = notificationDeliveryEnabled;
        int safeDeleteDays = Math.max(1, deleteAfterDays);
        int safeNoticeDays = Math.max(0, Math.min(noticeAfterDays, safeDeleteDays));
        this.noticeAfter = Duration.ofDays(safeNoticeDays);
        this.deleteAfter = Duration.ofDays(safeDeleteDays);
        this.minimumNotice = Duration.ofDays(Math.max(1, minimumNoticeDays));
        this.retryDelay = Duration.ofHours(Math.max(1, noticeRetryHours));
        this.subjectHashPepper = subjectHashPepper == null ? "" : subjectHashPepper;
    }

    @PostConstruct
    void validateDeliveryConfiguration() {
        if (notificationDeliveryEnabled && noticeGateway == null) {
            throw new IllegalStateException("账号删除通知已经启用，但没有配置邮件发送实现");
        }
    }

    public PolicyRun runScheduled(Instant now, int batchSize) {
        if (!policyEvaluationEnabled) return new PolicyRun(false, 0, 0, 0);
        Instant effectiveNow = now == null ? Instant.now() : now;
        int safeBatch = Math.max(1, Math.min(batchSize, 500));
        int prepared = prepareNotices(effectiveNow, safeBatch);
        int delivered = dispatchNotices(effectiveNow, safeBatch);
        int deletionJobs = prepareDeletionJobs(effectiveNow, safeBatch);
        return new PolicyRun(true, prepared, delivered, deletionJobs);
    }

    int prepareNotices(Instant now, int limit) {
        List<String> candidates = jdbc.query("""
                select r.user_id from account_retention_state r
                join app_user u on u.id=r.user_id and u.status='ACTIVE'
                where r.policy_state='ACTIVE' and r.last_meaningful_activity_at<=?
                order by r.last_meaningful_activity_at,r.user_id limit ?
                """, (row, index) -> row.getString(1), Timestamp.from(now.minus(noticeAfter)), limit);
        int prepared = 0;
        for (String userId : candidates) {
            Boolean created = transactions.execute(status -> prepareNotice(userId, now));
            if (Boolean.TRUE.equals(created)) prepared++;
        }
        return prepared;
    }

    private boolean prepareNotice(String userId, Instant now) {
        RetentionState state = jdbc.query("""
                        select last_meaningful_activity_at,version,policy_state
                        from account_retention_state where user_id=? for update
                        """, (row, index) -> new RetentionState(row.getTimestamp(1).toInstant(),
                        row.getLong(2), row.getString(3)), userId).stream().findFirst().orElse(null);
        if (state == null || !"ACTIVE".equals(state.policyState())
                || state.activityAt().isAfter(now.minus(noticeAfter))) return false;
        Integer existing = jdbc.queryForObject("""
                select count(*) from account_deletion_notice where user_id=? and activity_version=?
                """, Integer.class, userId, state.version());
        if (existing != null && existing > 0) return false;
        Instant scheduled = latest(state.activityAt().plus(deleteAfter), now.plus(minimumNotice));
        Timestamp timestamp = Timestamp.from(now);
        String noticeId = "account-notice-" + UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into account_deletion_notice(
                        id,user_id,activity_version,scheduled_deletion_at,status,attempt_count,next_attempt_at,
                        lease_owner,lease_expires_at,last_error_code,created_at,updated_at,sent_at,canceled_at
                    ) values (?,?,?,?,'PENDING',0,?,null,null,null,?,?,null,null)
                    """, noticeId, userId, state.version(),
                    Timestamp.from(scheduled), timestamp, timestamp, timestamp);
        } catch (DuplicateKeyException race) {
            return false;
        }
        int changed = jdbc.update("""
                update account_retention_state
                set policy_state='NOTICE_DUE',scheduled_deletion_at=?,updated_at=?
                where user_id=? and version=? and policy_state='ACTIVE'
                """, Timestamp.from(scheduled), timestamp, userId, state.version());
        if (changed != 1) throw new IllegalStateException("账号活动在创建通知时发生变化");
        events.noticePrepared(noticeId, now);
        return true;
    }

    int dispatchNotices(Instant now, int limit) {
        if (!notificationDeliveryEnabled || noticeGateway == null) return 0;
        List<String> candidates = jdbc.query("""
                select id from account_deletion_notice
                where (status in ('PENDING','RETRYABLE_FAILED') and (next_attempt_at is null or next_attempt_at<=?)
                       and (lease_expires_at is null or lease_expires_at<=?))
                   or (status='SENDING' and lease_expires_at<=?)
                order by coalesce(next_attempt_at,created_at),id limit ?
                """, (row, index) -> row.getString(1), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), limit);
        int delivered = 0;
        for (String noticeId : candidates) {
            ClaimedNotice notice = transactions.execute(status -> claimNotice(noticeId, now));
            if (notice == null) continue;
            try {
                noticeGateway.send(message(notice));
                Boolean completed = transactions.execute(status -> completeNotice(notice, now));
                if (Boolean.TRUE.equals(completed)) delivered++;
            } catch (RuntimeException failure) {
                markNoticeRetryable(notice.id(), now, stableErrorCode(failure));
            }
        }
        return delivered;
    }

    private ClaimedNotice claimNotice(String noticeId, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        int claimed = jdbc.update("""
                update account_deletion_notice
                set status='SENDING',attempt_count=attempt_count+1,lease_owner=?,lease_expires_at=?,updated_at=?
                where id=? and ((status in ('PENDING','RETRYABLE_FAILED')
                    and (next_attempt_at is null or next_attempt_at<=?)
                    and (lease_expires_at is null or lease_expires_at<=?))
                    or (status='SENDING' and lease_expires_at<=?))
                """, leaseOwner, Timestamp.from(now.plus(Duration.ofMinutes(5))), timestamp,
                noticeId, timestamp, timestamp, timestamp);
        if (claimed != 1) return null;
        return jdbc.query("""
                        select n.id,n.user_id,n.activity_version,n.scheduled_deletion_at,u.email,n.attempt_count
                        from account_deletion_notice n
                        join account_retention_state r on r.user_id=n.user_id and r.version=n.activity_version
                        join app_user u on u.id=n.user_id and u.status='ACTIVE'
                        where n.id=? and n.status='SENDING' and r.policy_state='NOTICE_DUE'
                        """, (row, index) -> new ClaimedNotice(row.getString(1), row.getString(2),
                        row.getLong(3), row.getTimestamp(4).toInstant(), row.getString(5), row.getInt(6)), noticeId)
                .stream().findFirst().orElse(null);
    }

    private boolean completeNotice(ClaimedNotice notice, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        int completed = jdbc.update("""
                update account_deletion_notice
                set status='SENT',lease_owner=null,lease_expires_at=null,last_error_code=null,
                    next_attempt_at=null,updated_at=?,sent_at=?
                where id=? and user_id=? and activity_version=? and status='SENDING'
                """, timestamp, timestamp, notice.id(), notice.userId(), notice.activityVersion());
        if (completed != 1) return false;
        int advanced = jdbc.update("""
                update account_retention_state
                set policy_state='NOTICE_SENT',notice_sent_at=?,updated_at=?
                where user_id=? and version=? and policy_state='NOTICE_DUE'
                """, timestamp, timestamp, notice.userId(), notice.activityVersion());
        if (advanced != 1) throw new IllegalStateException("通知发送后账号活动状态已经变化");
        events.noticeSent(notice.id(), notice.attemptCount(), now);
        return true;
    }

    private void markNoticeRetryable(String noticeId, Instant now, String errorCode) {
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    update account_deletion_notice
                    set status='RETRYABLE_FAILED',lease_owner=null,lease_expires_at=null,last_error_code=?,
                        next_attempt_at=?,updated_at=? where id=? and status='SENDING'
                    """, errorCode, Timestamp.from(now.plus(retryDelay)), Timestamp.from(now), noticeId);
            if (changed != 1) return;
            Integer attemptCount = jdbc.queryForObject(
                    "select attempt_count from account_deletion_notice where id=?", Integer.class, noticeId);
            events.noticeRetryableFailed(noticeId, attemptCount == null ? 0 : attemptCount, errorCode, now);
        });
    }

    int prepareDeletionJobs(Instant now, int limit) {
        List<String> candidates = jdbc.query("""
                select r.user_id from account_retention_state r
                join account_deletion_notice n on n.user_id=r.user_id and n.activity_version=r.version
                join app_user u on u.id=r.user_id and u.status='ACTIVE'
                where r.policy_state='NOTICE_SENT' and n.status='SENT' and r.scheduled_deletion_at<=?
                order by r.scheduled_deletion_at,r.user_id limit ?
                """, (row, index) -> row.getString(1), Timestamp.from(now), limit);
        int prepared = 0;
        for (String userId : candidates) {
            Boolean created = transactions.execute(status -> prepareDeletionJob(userId, now));
            if (Boolean.TRUE.equals(created)) prepared++;
        }
        return prepared;
    }

    private boolean prepareDeletionJob(String userId, Instant now) {
        RetentionState state = jdbc.query("""
                        select last_meaningful_activity_at,version,policy_state
                        from account_retention_state where user_id=? for update
                        """, (row, index) -> new RetentionState(row.getTimestamp(1).toInstant(),
                        row.getLong(2), row.getString(3)), userId).stream().findFirst().orElse(null);
        if (state == null || !"NOTICE_SENT".equals(state.policyState())) return false;
        Integer dueNotice = jdbc.queryForObject("""
                select count(*) from account_deletion_notice
                where user_id=? and activity_version=? and status='SENT' and scheduled_deletion_at<=?
                """, Integer.class, userId, state.version(), Timestamp.from(now));
        if (dueNotice == null || dueNotice != 1) return false;
        Integer existing = jdbc.queryForObject("""
                select count(*) from account_deletion_job
                where user_id=? and reason='INACTIVITY' and status in ('DELETE_DUE','DELETING','RETRYABLE_FAILED')
                """, Integer.class, userId);
        if (existing != null && existing > 0) return false;
        Timestamp timestamp = Timestamp.from(now);
        String jobId = "account-delete-" + UUID.randomUUID();
        jdbc.update("""
                insert into account_deletion_job(
                    id,user_id,subject_digest,reason,status,expected_activity_before,lease_owner,lease_expires_at,
                    attempt_count,last_error_code,delete_counts_json,requested_at,updated_at,completed_at
                ) values (?,?,?,'INACTIVITY','DELETE_DUE',?,null,null,0,null,'{}',?,?,null)
                """, jobId, userId, subjectDigest(userId),
                Timestamp.from(state.activityAt()), timestamp, timestamp);
        int changed = jdbc.update("""
                update account_retention_state set policy_state='DELETE_DUE',updated_at=?
                where user_id=? and version=? and policy_state='NOTICE_SENT'
                """, timestamp, userId, state.version());
        if (changed != 1) throw new IllegalStateException("创建删除任务时账号活动状态已经变化");
        events.deletionDue(jobId, "INACTIVITY", now);
        return true;
    }

    private AccountDeletionNoticeGateway.NoticeMessage message(ClaimedNotice notice) {
        String deletionTime = NOTICE_TIME.format(notice.scheduledDeletionAt());
        String body = "你的《问仙录》账号已达到不活跃预通知条件。若在 " + deletionTime
                + " 前没有新的有意义活动，账号、命卷、模型凭据和相关个人数据将进入删除流程。\n\n"
                + "在删除前成功登录、创建命卷、提交回合、修改密码或变更模型凭据，都会自动取消本次计划。"
                + "如果你刚刚已经恢复使用，可以忽略本通知。\n\n本邮件不要求回复，也不会索取密码或模型密钥。";
        return new AccountDeletionNoticeGateway.NoticeMessage(notice.id(), notice.email(),
                "《问仙录》账号数据删除预通知", body, notice.scheduledDeletionAt());
    }

    private Instant latest(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private String subjectDigest(String userId) {
        return TokenHasher.sha256(subjectHashPepper + ":" + userId);
    }

    private String stableErrorCode(RuntimeException failure) {
        return failure.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    RuntimeSettings runtimeSettings() {
        return new RuntimeSettings(policyEvaluationEnabled, notificationDeliveryEnabled,
                noticeGateway != null, Math.toIntExact(noticeAfter.toDays()),
                Math.toIntExact(deleteAfter.toDays()), Math.toIntExact(minimumNotice.toDays()));
    }

    public record PolicyRun(boolean enabled, int noticesPrepared, int noticesDelivered, int deletionJobsPrepared) { }
    record RuntimeSettings(boolean policyEvaluationEnabled, boolean notificationDeliveryEnabled,
                           boolean mailGatewayReady, int noticeAfterDays, int deleteAfterDays,
                           int minimumNoticeDays) { }
    private record RetentionState(Instant activityAt, long version, String policyState) { }
    private record ClaimedNotice(String id, String userId, long activityVersion, Instant scheduledDeletionAt,
                                 String email, int attemptCount) { }
}

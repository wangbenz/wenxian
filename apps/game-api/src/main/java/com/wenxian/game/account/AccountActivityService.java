package com.wenxian.game.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 维护账号留存领域唯一的“有意义活动”时间，并原子取消尚未开始执行的删除计划。
 *
 * <p>调用方必须只在登录、账号安全、建卷、成功回合和 BYOK 变更已经成功时调用。该服务不接受
 * 页面访问、会话续期、失败模型调用或统计回填冒充实时活动；外层事务回滚时活动记录也必须回滚。</p>
 */
@Service
public class AccountActivityService {
    private final JdbcTemplate jdbc;
    private final AccountLifecycleEventRepository events;

    @Autowired
    public AccountActivityService(JdbcTemplate jdbc, AccountLifecycleEventRepository events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** 仅供内嵌数据库测试使用。 */
    public AccountActivityService(JdbcTemplate jdbc) {
        this(jdbc, new AccountLifecycleEventRepository(jdbc, new ObjectMapper()));
    }

    @Transactional
    public void record(String userId, AccountActivitySource source) {
        record(userId, source, Instant.now());
    }

    @Transactional
    public void record(String userId, AccountActivitySource source, Instant occurredAt) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("账号ID不能为空");
        if (source == null) throw new IllegalArgumentException("活动来源不能为空");
        Instant activityAt = occurredAt == null ? Instant.now() : occurredAt;
        Timestamp timestamp = Timestamp.from(activityAt);
        int changed = jdbc.update("""
                update account_retention_state
                set last_meaningful_activity_at=?, activity_source=?, policy_state='ACTIVE',
                    notice_sent_at=null, scheduled_deletion_at=null,
                    canceled_at=case when policy_state<>'ACTIVE' then ? else canceled_at end,
                    activity_origin='REALTIME', version=version+1, updated_at=?
                where user_id=? and last_meaningful_activity_at<=?
                """, timestamp, source.name(), timestamp, timestamp, userId, timestamp);
        boolean accepted = changed > 0 || insertOrRefresh(userId, source, timestamp);
        if (!accepted) return;
        cancelDeletionJobs(userId, source, timestamp, activityAt);
        cancelNotices(userId, source, timestamp, activityAt);
    }

    private void cancelDeletionJobs(String userId, AccountActivitySource source,
                                    Timestamp timestamp, Instant occurredAt) {
        List<CancelableJob> jobs = jdbc.query("""
                select id,reason from account_deletion_job
                where user_id=? and status in ('NOTICE_DUE','NOTICE_SENT','DELETE_DUE','RETRYABLE_FAILED')
                """, (row, index) -> new CancelableJob(row.getString(1), row.getString(2)), userId);
        for (CancelableJob job : jobs) {
            int canceled = jdbc.update("""
                    update account_deletion_job
                    set user_id=null,status='CANCELED',lease_owner=null,lease_expires_at=null,
                        last_error_code=null,updated_at=?,completed_at=?
                    where id=? and user_id=?
                      and status in ('NOTICE_DUE','NOTICE_SENT','DELETE_DUE','RETRYABLE_FAILED')
                    """, timestamp, timestamp, job.id(), userId);
            if (canceled == 1) events.deletionCanceled(job.id(), job.reason(), source.name(), occurredAt);
        }
    }

    private void cancelNotices(String userId, AccountActivitySource source,
                               Timestamp timestamp, Instant occurredAt) {
        List<String> notices = jdbc.query("""
                select id from account_deletion_notice
                where user_id=? and status in ('PENDING','SENDING','SENT','RETRYABLE_FAILED')
                """, (row, index) -> row.getString(1), userId);
        for (String noticeId : notices) {
            int canceled = jdbc.update("""
                    update account_deletion_notice
                    set user_id=null,status='CANCELED',lease_owner=null,lease_expires_at=null,
                        last_error_code=null,updated_at=?,canceled_at=?
                    where id=? and user_id=? and status in ('PENDING','SENDING','SENT','RETRYABLE_FAILED')
                    """, timestamp, timestamp, noticeId, userId);
            if (canceled == 1) events.noticeCanceled(noticeId, source.name(), occurredAt);
        }
    }

    private boolean insertOrRefresh(String userId, AccountActivitySource source, Timestamp timestamp) {
        Integer exists = jdbc.queryForObject(
                "select count(*) from account_retention_state where user_id=?", Integer.class, userId);
        if (exists != null && exists > 0) return false;
        try {
            jdbc.update("""
                    insert into account_retention_state(
                        user_id,last_meaningful_activity_at,activity_source,policy_state,notice_sent_at,
                        scheduled_deletion_at,canceled_at,activity_origin,version,updated_at
                    ) values (?,?,?,'ACTIVE',null,null,null,'REALTIME',0,?)
                    """, userId, timestamp, source.name(), timestamp);
            return true;
        } catch (DuplicateKeyException concurrentInsert) {
            return jdbc.update("""
                    update account_retention_state
                    set last_meaningful_activity_at=?, activity_source=?, policy_state='ACTIVE',
                        notice_sent_at=null, scheduled_deletion_at=null, activity_origin='REALTIME',
                        version=version+1, updated_at=?
                    where user_id=? and last_meaningful_activity_at<=?
                    """, timestamp, source.name(), timestamp, userId, timestamp) > 0;
        }
    }

    private record CancelableJob(String id, String reason) { }
}

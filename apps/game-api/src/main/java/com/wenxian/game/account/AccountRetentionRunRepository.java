package com.wenxian.game.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * 持久化账号留存调度的非个人运行证据。
 *
 * <p>记录开关、阈值、批量计数和稳定错误码，不记录本批账号列表或原始异常。开始记录成功后，
 * 结束必须收敛为 SUCCEEDED、SKIPPED 或 FAILED，供管理台判断调度是否真实运行。</p>
 */
@Repository
public class AccountRetentionRunRepository {
    private final JdbcTemplate jdbc;

    public AccountRetentionRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String start(String triggerSource, RunSettings settings, Instant startedAt) {
        String runId = "account-retention-run-" + UUID.randomUUID();
        jdbc.update("""
                insert into account_retention_run(
                    id,trigger_source,status,policy_evaluation_enabled,notification_delivery_enabled,
                    automatic_deletion_enabled,mail_gateway_ready,notice_after_days,delete_after_days,
                    minimum_notice_days,batch_size,notices_prepared,notices_delivered,deletion_jobs_prepared,
                    error_code,started_at,finished_at
                ) values (?,?,'RUNNING',?,?,?,?,?,?,?,?,0,0,0,null,?,null)
                """, runId, triggerSource, settings.policyEvaluationEnabled(),
                settings.notificationDeliveryEnabled(), settings.automaticDeletionEnabled(),
                settings.mailGatewayReady(), settings.noticeAfterDays(), settings.deleteAfterDays(),
                settings.minimumNoticeDays(), settings.batchSize(), Timestamp.from(startedAt));
        return runId;
    }

    public void complete(String runId, AccountRetentionPolicyService.PolicyRun result, Instant finishedAt) {
        String status = result.enabled() ? "SUCCEEDED" : "SKIPPED";
        int changed = jdbc.update("""
                update account_retention_run
                set status=?,notices_prepared=?,notices_delivered=?,deletion_jobs_prepared=?,
                    error_code=null,finished_at=? where id=? and status='RUNNING'
                """, status, result.noticesPrepared(), result.noticesDelivered(),
                result.deletionJobsPrepared(), Timestamp.from(finishedAt), runId);
        if (changed != 1) throw new IllegalStateException("账号留存调度完成状态写入失败");
    }

    public void fail(String runId, RuntimeException failure, Instant finishedAt) {
        int changed = jdbc.update("""
                update account_retention_run set status='FAILED',error_code=?,finished_at=?
                where id=? and status='RUNNING'
                """, stableErrorCode(failure), Timestamp.from(finishedAt), runId);
        if (changed != 1) throw new IllegalStateException("账号留存调度失败状态写入失败");
    }

    private String stableErrorCode(RuntimeException failure) {
        return failure.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    public record RunSettings(
            boolean policyEvaluationEnabled,
            boolean notificationDeliveryEnabled,
            boolean automaticDeletionEnabled,
            boolean mailGatewayReady,
            int noticeAfterDays,
            int deleteAfterDays,
            int minimumNoticeDays,
            int batchSize
    ) { }
}

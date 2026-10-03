package com.wenxian.game.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 小批量触发账号留存评估；所有真实行为仍由 {@link AccountRetentionPolicyService} 的独立开关保护。
 * 调度异常只终止本批，不得影响登录、创角或回合请求。
 */
@Component
public class AccountRetentionScheduler {
    private final AccountRetentionPolicyService policy;
    private final AccountRetentionRunRepository runs;
    private final int batchSize;
    private final boolean automaticDeletionEnabled;

    public AccountRetentionScheduler(
            AccountRetentionPolicyService policy,
            AccountRetentionRunRepository runs,
            @Value("${game.account-retention.scheduler-batch-size:50}") int batchSize,
            @Value("${game.account-retention.automatic-deletion-enabled:false}") boolean automaticDeletionEnabled) {
        this.policy = policy;
        this.runs = runs;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
        this.automaticDeletionEnabled = automaticDeletionEnabled;
    }

    @Scheduled(initialDelayString = "${game.account-retention.scheduler-initial-delay-ms:120000}",
            fixedDelayString = "${game.account-retention.scheduler-delay-ms:300000}")
    void run() {
        Instant startedAt = Instant.now();
        AccountRetentionPolicyService.RuntimeSettings runtime = policy.runtimeSettings();
        String runId = runs.start("SCHEDULED", new AccountRetentionRunRepository.RunSettings(
                runtime.policyEvaluationEnabled(), runtime.notificationDeliveryEnabled(),
                automaticDeletionEnabled, runtime.mailGatewayReady(), runtime.noticeAfterDays(),
                runtime.deleteAfterDays(), runtime.minimumNoticeDays(), batchSize), startedAt);
        try {
            AccountRetentionPolicyService.PolicyRun result = policy.runScheduled(startedAt, batchSize);
            runs.complete(runId, result, Instant.now());
        } catch (RuntimeException failure) {
            runs.fail(runId, failure, Instant.now());
            throw failure;
        }
    }
}

package com.wenxian.game.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 写入不可反查玩家身份的账号生命周期事件。
 *
 * <p>公开方法只接受随机通知/删除任务 ID、稳定枚举、尝试次数和分域行数，避免调用方把账号 ID、
 * 邮箱、摘要、正文、密钥或原始异常塞进元数据。事件键重复时视为状态重试，不重复计数。</p>
 */
@Repository
public class AccountLifecycleEventRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AccountLifecycleEventRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void noticePrepared(String noticeId, Instant occurredAt) {
        append(noticeId + ":NOTICE_PREPARED", AccountLifecycleEventType.NOTICE_PREPARED,
                null, noticeId, null, Map.of(), occurredAt);
    }

    public void noticeSent(String noticeId, int attemptCount, Instant occurredAt) {
        append(noticeId + ":NOTICE_SENT", AccountLifecycleEventType.NOTICE_SENT,
                null, noticeId, null, Map.of("attemptCount", attemptCount), occurredAt);
    }

    public void noticeRetryableFailed(String noticeId, int attemptCount, String errorCode, Instant occurredAt) {
        append(noticeId + ":NOTICE_RETRYABLE_FAILED:" + attemptCount,
                AccountLifecycleEventType.NOTICE_RETRYABLE_FAILED, null, noticeId, errorCode,
                Map.of("attemptCount", attemptCount), occurredAt);
    }

    public void noticeCanceled(String noticeId, String activitySource, Instant occurredAt) {
        append(noticeId + ":NOTICE_CANCELED", AccountLifecycleEventType.NOTICE_CANCELED,
                null, noticeId, null, Map.of("activitySource", activitySource), occurredAt);
    }

    public void deletionDue(String jobId, String reason, Instant occurredAt) {
        append(jobId + ":DELETE_DUE", AccountLifecycleEventType.DELETE_DUE,
                reason, jobId, null, Map.of(), occurredAt);
    }

    public void deletionStarted(String jobId, String reason, int attemptCount, Instant occurredAt) {
        append(jobId + ":DELETION_STARTED:" + attemptCount, AccountLifecycleEventType.DELETION_STARTED,
                reason, jobId, null, Map.of("attemptCount", attemptCount), occurredAt);
    }

    public void deletionCompleted(String jobId, String reason, int attemptCount,
                                  Map<String, Integer> deleteCounts, Instant occurredAt) {
        append(jobId + ":DELETION_COMPLETED", AccountLifecycleEventType.DELETION_COMPLETED,
                reason, jobId, null, Map.of("attemptCount", attemptCount,
                        "deleteCounts", Map.copyOf(deleteCounts)), occurredAt);
    }

    public void deletionRetryableFailed(String jobId, String reason, int attemptCount,
                                        String errorCode, Instant occurredAt) {
        append(jobId + ":DELETION_RETRYABLE_FAILED:" + attemptCount,
                AccountLifecycleEventType.DELETION_RETRYABLE_FAILED, reason, jobId, errorCode,
                Map.of("attemptCount", attemptCount), occurredAt);
    }

    public void deletionCanceled(String jobId, String reason, String activitySource, Instant occurredAt) {
        append(jobId + ":DELETION_CANCELED", AccountLifecycleEventType.DELETION_CANCELED,
                reason, jobId, null, Map.of("activitySource", activitySource), occurredAt);
    }

    private void append(String eventKey, AccountLifecycleEventType eventType, String reason,
                        String referenceId, String errorCode, Map<String, ?> metadata, Instant occurredAt) {
        if (referenceId == null || referenceId.isBlank()) {
            throw new IllegalArgumentException("生命周期事件必须关联随机任务ID");
        }
        try {
            jdbc.update("""
                    insert into account_lifecycle_event(
                        id,event_key,event_type,reason,reference_id,error_code,metadata_json,occurred_at
                    ) values (?,?,?,?,?,?,?,?)
                    """, "account-event-" + UUID.randomUUID(), eventKey, eventType.name(), reason,
                    referenceId, errorCode, json(metadata), Timestamp.from(occurredAt));
        } catch (DuplicateKeyException duplicate) {
            // 同一状态更新重试只保留一条长期事件。
        }
    }

    private String json(Map<String, ?> metadata) {
        try {
            return mapper.writeValueAsString(metadata);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("账号生命周期匿名元数据无法序列化", error);
        }
    }
}

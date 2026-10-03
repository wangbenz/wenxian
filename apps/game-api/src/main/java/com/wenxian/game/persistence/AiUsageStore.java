package com.wenxian.game.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.wenxian.game.service.GameVersionService;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class AiUsageStore {
    private final JdbcTemplate jdbc;
    private final GameVersionService gameVersionService;

    public AiUsageStore(JdbcTemplate jdbc, GameVersionService gameVersionService) {
        this.jdbc = jdbc;
        this.gameVersionService = gameVersionService;
    }

    /**
     * 记录中性模型任务的一次真实供应商请求。
     *
     * <p>同一 requestId 下可以存在主模型和一次降级请求，attemptNumber 用于还原调用顺序。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordModelAttempt(String requestId, String userId, int attemptNumber, String operation,
                                   String provider, String credentialSource, String model, boolean fallbackUsed,
                                   String status, int promptTokens, int completionTokens,
                                   int cacheHitTokens, int cacheMissTokens, long latencyMs, String error) {
        jdbc.update("""
                insert into ai_usage_log
                (id, request_id, user_id, attribution_status, attempt_number, fallback_used, save_id, turn_number, operation,
                 provider, credential_source, model, game_version, status, prompt_tokens, completion_tokens,
                 cache_hit_tokens, cache_miss_tokens, latency_ms, error_detail, created_at)
                values (?, ?, ?, 'ACCOUNT_LINKED', ?, ?, null, null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "usage-" + UUID.randomUUID(), requestId, userId, attemptNumber, fallbackUsed, operation,
                provider, credentialSource, model, gameVersionService.current(), status, promptTokens,
                completionTokens, cacheHitTokens, cacheMissTokens, latencyMs, trim(error),
                Timestamp.from(Instant.now()));
    }

    private String trim(String value) {
        if (value == null) return "";
        return value.substring(0, Math.min(1000, value.length()));
    }
}

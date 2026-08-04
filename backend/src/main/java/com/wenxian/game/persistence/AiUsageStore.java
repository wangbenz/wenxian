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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String saveId, Integer turnNumber, String operation, String provider, String credentialSource,
                       String model, String status, int promptTokens, int completionTokens,
                       int cacheHitTokens, int cacheMissTokens, long latencyMs, String error) {
        jdbc.update("""
                insert into ai_usage_log
                (id, save_id, turn_number, operation, provider, credential_source, model, game_version, status,
                 prompt_tokens, completion_tokens, cache_hit_tokens, cache_miss_tokens,
                 latency_ms, error_detail, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "usage-" + UUID.randomUUID(), saveId, turnNumber, operation,
                provider, credentialSource, model, gameVersionService.current(), status, promptTokens, completionTokens,
                cacheHitTokens, cacheMissTokens, latencyMs, trim(error), Timestamp.from(Instant.now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordVersion(String gameVersion, String saveId, Integer turnNumber, String operation,
                              String provider, String credentialSource, String model, String status,
                              int promptTokens, int completionTokens, long latencyMs, String error) {
        jdbc.update("""
                insert into ai_usage_log
                (id, save_id, turn_number, operation, provider, credential_source, model, game_version, status,
                 prompt_tokens, completion_tokens, cache_hit_tokens, cache_miss_tokens,
                 latency_ms, error_detail, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?)
                """, "usage-" + UUID.randomUUID(), saveId, turnNumber, operation,
                provider, credentialSource, model, gameVersion, status, promptTokens, completionTokens,
                latencyMs, trim(error), Timestamp.from(Instant.now()));
    }

    private String trim(String value) {
        if (value == null) return "";
        return value.substring(0, Math.min(1000, value.length()));
    }
}

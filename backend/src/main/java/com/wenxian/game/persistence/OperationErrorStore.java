package com.wenxian.game.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class OperationErrorStore {
    private final JdbcTemplate jdbc;

    public OperationErrorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String referenceId, String saveId, String operation, String category, String code,
                       int httpStatus, String publicMessage, String diagnostic) {
        jdbc.update("""
                insert into operation_error
                (reference_id, save_id, operation, category, error_code, http_status,
                 public_message, diagnostic, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, referenceId, clean(saveId, 80), clean(operation, 120), clean(category, 32),
                clean(code, 64), httpStatus, clean(publicMessage, 600), clean(diagnostic, 2000),
                Timestamp.from(Instant.now()));
    }

    private String clean(String value, int limit) {
        if (value == null) return "";
        String normalized = value.replaceAll("(?i)sk-[a-z0-9_-]{12,}", "<REDACTED_KEY>");
        return normalized.substring(0, Math.min(limit, normalized.length()));
    }
}

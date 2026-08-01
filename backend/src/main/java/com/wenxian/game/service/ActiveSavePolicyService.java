package com.wenxian.game.service;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

@Service
public class ActiveSavePolicyService {
    public static final String CONFIG_KEY = "max_active_saves";
    private static final int DEFAULT_MAXIMUM = 2;

    private final JdbcTemplate jdbcTemplate;

    public ActiveSavePolicyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void ensureDefault() {
        try {
            jdbcTemplate.update("""
                    insert into gameplay_config(config_key, config_value, updated_at)
                    values (?, ?, ?)
                    """, CONFIG_KEY, String.valueOf(DEFAULT_MAXIMUM), Timestamp.from(Instant.now()));
        } catch (DataAccessException alreadyExists) {
            // Existing environments keep the operator-selected value.
        }
    }

    public int maximum() {
        String value = jdbcTemplate.queryForObject(
                "select config_value from gameplay_config where config_key=?", String.class, CONFIG_KEY);
        return normalize(value);
    }

    public int lockMaximum() {
        String value = jdbcTemplate.queryForObject(
                "select config_value from gameplay_config where config_key=? for update", String.class, CONFIG_KEY);
        return normalize(value);
    }

    private int normalize(String value) {
        try {
            return Math.max(1, Math.min(2, Integer.parseInt(value)));
        } catch (RuntimeException ignored) {
            return DEFAULT_MAXIMUM;
        }
    }
}

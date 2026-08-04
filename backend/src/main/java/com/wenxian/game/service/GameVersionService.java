package com.wenxian.game.service;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

@Service
public class GameVersionService {
    public static final String CONFIG_KEY = "current_game_version";
    public static final String DEFAULT_VERSION = "V0.14";

    private final JdbcTemplate jdbc;

    public GameVersionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void ensureDefault() {
        try {
            jdbc.update("""
                    insert into gameplay_config(config_key, config_value, updated_at)
                    values (?, ?, ?)
                    """, CONFIG_KEY, DEFAULT_VERSION, Timestamp.from(Instant.now()));
        } catch (DataAccessException alreadyExists) {
            // Preserve the version explicitly selected for the current environment.
        }
    }

    public String current() {
        return jdbc.query("select config_value from gameplay_config where config_key=?",
                (row, ignored) -> row.getString(1), CONFIG_KEY)
                .stream().findFirst().filter(value -> !value.isBlank()).orElse(DEFAULT_VERSION);
    }

    public boolean legacyWritesAllowed() {
        return versionNumber(current()) < versionNumber(DEFAULT_VERSION);
    }

    private static int versionNumber(String value) {
        if (value == null) return 0;
        String normalized = value.trim().toUpperCase().replaceFirst("^V", "");
        String[] parts = normalized.split("\\.");
        if (parts.length < 2) return 0;
        try {
            return Integer.parseInt(parts[0]) * 10_000 + Integer.parseInt(parts[1]) * 100;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}

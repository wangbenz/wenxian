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
    public static final String DEFAULT_VERSION = "V0.13";

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
}

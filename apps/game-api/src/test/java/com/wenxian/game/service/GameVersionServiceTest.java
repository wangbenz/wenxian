package com.wenxian.game.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameVersionServiceTest {
    @Test
    void initializesNeutralVersionForANewEnvironment() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.execute("create table gameplay_config(config_key varchar(64) primary key, config_value varchar(255) not null, updated_at timestamp not null)");

            GameVersionService service = new GameVersionService(jdbc);
            service.ensureDefault();

            assertEquals("UNRELEASED", service.current());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void readsTheEnvironmentVersionFromSharedGameplayConfig() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.execute("create table gameplay_config(config_key varchar(64) primary key, config_value varchar(255) not null, updated_at timestamp not null)");
            jdbc.update("insert into gameplay_config values ('current_game_version','V0.12',?)",
                    Timestamp.from(Instant.now()));

            GameVersionService service = new GameVersionService(jdbc);
            service.ensureDefault();

            assertEquals("V0.12", service.current());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void usesProcessOverrideWithoutChangingSharedVersion() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.execute("create table gameplay_config(config_key varchar(64) primary key, config_value varchar(255) not null, updated_at timestamp not null)");
            jdbc.update("insert into gameplay_config values ('current_game_version','V0.14',?)",
                    Timestamp.from(Instant.now()));

            GameVersionService service = new GameVersionService(jdbc, "V0.15");

            assertEquals("V0.15", service.current());
            assertEquals("V0.14", jdbc.queryForObject(
                    "select config_value from gameplay_config where config_key='current_game_version'", String.class));
        } finally {
            database.shutdown();
        }
    }
}

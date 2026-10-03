package com.wenxian.admin;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminServiceVersionTest {
    @Test
    void updatesTheSharedVersionAndRejectsInvalidFormats() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.execute("create table gameplay_config(config_key varchar(64) primary key, config_value varchar(255) not null, updated_at timestamp not null)");
            jdbc.update("insert into gameplay_config values ('current_game_version','V0.12',?)",
                    Timestamp.from(Instant.now()));
            AdminService service = new AdminService(jdbc);

            assertEquals("V0.12", service.appVersion().get("version"));
            assertEquals("V0.13", service.updateAppVersion("v0.13").get("version"));
            assertEquals("V0.13", jdbc.queryForObject(
                    "select config_value from gameplay_config where config_key='current_game_version'", String.class));
            assertThrows(ResponseStatusException.class, () -> service.updateAppVersion("下一版"));
        } finally {
            database.shutdown();
        }
    }
}

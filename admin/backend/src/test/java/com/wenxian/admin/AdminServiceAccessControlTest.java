package com.wenxian.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminServiceAccessControlTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private AdminService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("admin-access-" + UUID.randomUUID()).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table gameplay_config(config_key varchar(64) primary key,config_value varchar(500) not null,updated_at timestamp not null)");
        jdbc.execute("create table app_user(id varchar(64) primary key,email varchar(255) not null,status varchar(32) not null)");
        jdbc.execute("create table game_access_whitelist(id varchar(64) primary key,user_id varchar(64) not null unique,note varchar(200) not null,created_by varchar(64) not null,created_at timestamp not null,updated_at timestamp not null)");
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into gameplay_config values ('game_access_mode','OPEN',?)", now);
        jdbc.update("insert into gameplay_config values ('game_access_notice_title','更新中',?)", now);
        jdbc.update("insert into gameplay_config values ('game_access_notice_content','稍后恢复',?)", now);
        jdbc.update("insert into gameplay_config values ('game_access_resume_at','',?)", now);
        jdbc.update("insert into app_user values ('user-1','tester@example.com','ACTIVE')");
        service = new AdminService(jdbc);
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    @Test
    void requiresAWhitelistBeforeRestrictingAndCanManageItByEmail() {
        assertThrows(ResponseStatusException.class,
                () -> service.updateAccessControl("WHITELIST", "更新中", "稍后恢复", ""));

        Map<String, Object> added = service.addAccessWhitelist("TESTER@example.com", "核心测试");
        assertEquals(1, ((java.util.List<?>) added.get("whitelist")).size());

        Map<String, Object> restricted = service.updateAccessControl(
                "WHITELIST", "维护命卷", "预计很快恢复", "2026-07-31T04:00:00Z");
        assertEquals("WHITELIST", restricted.get("mode"));

        assertThrows(ResponseStatusException.class, () -> service.removeAccessWhitelist("user-1"));
        service.updateAccessControl("OPEN", "维护命卷", "预计很快恢复", "");
        Map<String, Object> open = service.removeAccessWhitelist("user-1");
        assertEquals(0, ((java.util.List<?>) open.get("whitelist")).size());
    }
}

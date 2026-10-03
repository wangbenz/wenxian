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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminServiceAccountTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private AdminService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("admin-accounts-" + UUID.randomUUID())
                .build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("""
                create table admin_user (
                    id varchar(64) primary key,
                    username varchar(64) not null unique,
                    password_hash varchar(128) not null,
                    status varchar(16) not null,
                    created_at timestamp not null
                )
                """);
        jdbc.execute("""
                create table admin_session (
                    id varchar(64) primary key,
                    user_id varchar(64) not null,
                    token_hash varchar(128) not null unique,
                    expires_at timestamp not null,
                    created_at timestamp not null
                )
                """);
        jdbc.execute("""
                create table admin_operation_log (
                    id varchar(64) primary key,
                    operated_at timestamp not null,
                    operation varchar(64) not null,
                    detail varchar(1000) not null,
                    subject_user_id varchar(64)
                )
                """);
        service = new AdminService(jdbc);
        jdbc.update("insert into admin_user(id, username, password_hash, status, created_at) values (?, ?, ?, 'ACTIVE', ?)",
                "admin-root", "root_admin", AdminService.sha256("root-password"), Timestamp.from(Instant.now()));
        addSession("admin-root", "root-token");
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void createsListsDisablesAndResetsAdminAccountsSafely() {
        service.createAdminAccount("root-token", "operations_01", "initial-password");

        List<Map<String, Object>> accounts = service.adminAccounts("root-token");
        assertEquals(2, accounts.size());
        assertTrue(accounts.stream().anyMatch(account -> "root_admin".equals(account.get("username"))
                && Boolean.TRUE.equals(account.get("current"))));
        assertEquals(true, service.login("operations_01", "initial-password").get("ok"));

        String operationsId = jdbc.queryForObject(
                "select id from admin_user where username = 'operations_01'", String.class);
        service.updateAdminAccountStatus("root-token", operationsId, false);
        assertEquals("DISABLED", jdbc.queryForObject(
                "select status from admin_user where id = ?", String.class, operationsId));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from admin_session where user_id = ?", Integer.class, operationsId));
        assertThrows(ResponseStatusException.class, () -> service.login("operations_01", "initial-password"));

        service.updateAdminAccountStatus("root-token", operationsId, true);
        String temporaryPassword = service.resetAdminAccountPassword("root-token", operationsId);
        assertTrue(temporaryPassword.startsWith("Wx-"));
        assertThrows(ResponseStatusException.class, () -> service.login("operations_01", "initial-password"));
        assertEquals(true, service.login("operations_01", temporaryPassword).get("ok"));
        assertThrows(ResponseStatusException.class,
                () -> service.updateAdminAccountStatus("root-token", "admin-root", false));
    }

    @Test
    void rejectsDuplicateAndInvalidAdminNames() {
        service.createAdminAccount("root-token", "operations_01", "initial-password");
        assertThrows(ResponseStatusException.class,
                () -> service.createAdminAccount("root-token", "operations_01", "another-password"));
        assertThrows(ResponseStatusException.class,
                () -> service.createAdminAccount("root-token", "含空格 账号", "another-password"));
    }

    private void addSession(String userId, String token) {
        Instant now = Instant.now();
        jdbc.update("insert into admin_session(id, user_id, token_hash, expires_at, created_at) values (?, ?, ?, ?, ?)",
                "session-" + UUID.randomUUID(), userId, AdminService.sha256(token),
                Timestamp.from(now.plus(1, ChronoUnit.DAYS)), Timestamp.from(now));
    }
}

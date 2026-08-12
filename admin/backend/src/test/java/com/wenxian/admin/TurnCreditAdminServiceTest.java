package com.wenxian.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnCreditAdminServiceTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private TurnCreditAdminService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("turn-credit-admin-" + UUID.randomUUID())
                .build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("""
                create table app_user(
                    id varchar(64) primary key,email varchar(255) not null,status varchar(32) not null,
                    registered_at timestamp not null,last_login_at timestamp
                )
                """);
        jdbc.execute("create table gameplay_config(config_key varchar(64) primary key,config_value varchar(255) not null,updated_at timestamp not null)");
        jdbc.execute("create table user_daily_quota(user_id varchar(64),usage_date date,used_count integer not null,updated_at timestamp not null,primary key(user_id,usage_date))");
        jdbc.execute("create table admin_operation_log(id varchar(80) primary key,operated_at timestamp not null,operation varchar(64) not null,detail clob not null)");
        jdbc.execute("""
                create table turn_credit_grant(
                    id varchar(80) primary key,user_id varchar(64) not null,credit_class varchar(24) not null,
                    grant_type varchar(32) not null,campaign_code varchar(64),source_reference varchar(160),
                    idempotency_key varchar(180) not null unique,granted_count integer not null,
                    available_count integer not null,reserved_count integer not null,consumed_count integer not null,
                    expired_count integer not null,revoked_count integer not null,status varchar(24) not null,
                    expires_at timestamp,reason varchar(240) not null,created_by varchar(64) not null,
                    created_at timestamp not null,updated_at timestamp not null
                )
                """);
        jdbc.execute("""
                create table turn_credit_reservation(
                    id varchar(80) primary key,user_id varchar(64) not null,save_id varchar(80) not null,
                    request_id varchar(80) not null,grant_id varchar(80) not null,amount integer not null,
                    status varchar(24) not null,expires_at timestamp not null,turn_id varchar(80),
                    created_at timestamp not null,updated_at timestamp not null
                )
                """);
        jdbc.execute("""
                create table turn_credit_ledger(
                    id varchar(80) primary key,user_id varchar(64) not null,grant_id varchar(80) not null,
                    reservation_id varchar(80),event_type varchar(24) not null,event_key varchar(200) not null unique,
                    available_delta integer not null,reserved_delta integer not null,consumed_delta integer not null,
                    balance_after integer not null,business_reference varchar(160),operator_id varchar(64) not null,
                    remark varchar(240) not null,created_at timestamp not null
                )
                """);
        Instant now = Instant.now();
        jdbc.update("insert into app_user values ('user-1','player@example.com','ACTIVE',?,?)",
                Timestamp.from(now.minusSeconds(3600)), Timestamp.from(now));
        jdbc.update("insert into gameplay_config values ('account_public_turn_limit','20',?)", Timestamp.from(now));
        jdbc.update("insert into user_daily_quota values ('user-1',?,3,?)", Date.valueOf("2026-08-12"), Timestamp.from(now));
        service = new TurnCreditAdminService(jdbc);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void grantInitializesLegacyBalanceAndRemainsIdempotent() {
        var request = new TurnCreditAdminService.GrantRequest(
                "playtest-001", "PLAYTEST", 50, "V015_GRAY", "tester-1", null, "V0.15灰度测试");

        Map<String, Object> first = service.grant("user-1", request, "admin-1");
        Map<String, Object> second = service.grant("user-1", request, "admin-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> balance = (Map<String, Object>) first.get("balance");
        assertEquals(70, balance.get("granted"));
        assertEquals(67, balance.get("available"));
        assertEquals(3, balance.get("consumed"));
        assertEquals(first.get("grants"), second.get("grants"));
        assertEquals(2, jdbc.queryForObject("select count(*) from turn_credit_grant", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from turn_credit_ledger", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from admin_operation_log", Integer.class));
    }

    @Test
    void revokesOnlyUnusedSponsoredBalanceAndKeepsLedger() {
        service.grant("user-1", new TurnCreditAdminService.GrantRequest(
                "feedback-001", "FEEDBACK", 10, "", "WX-001", null, "有效问题反馈"), "admin-1");
        String grantId = jdbc.queryForObject(
                "select id from turn_credit_grant where grant_type='FEEDBACK'", String.class);

        Map<String, Object> result = service.revoke("user-1", grantId, "奖励发放错误", "admin-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> balance = (Map<String, Object>) result.get("balance");
        assertEquals(17, balance.get("available"));
        assertEquals(10, balance.get("revoked"));
        assertEquals("REVOKED", jdbc.queryForObject(
                "select status from turn_credit_grant where id=?", String.class, grantId));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from turn_credit_ledger where event_type='REVOKE'", Integer.class));
    }

    @Test
    void rejectsRevokeWhileGrantHasReservation() {
        service.grant("user-1", new TurnCreditAdminService.GrantRequest(
                "activity-001", "ACTIVITY", 5, "", "", null, "活动奖励"), "admin-1");
        String grantId = jdbc.queryForObject(
                "select id from turn_credit_grant where grant_type='ACTIVITY'", String.class);
        jdbc.update("update turn_credit_grant set available_count=4,reserved_count=1 where id=?", grantId);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.revoke("user-1", grantId, "撤销", "admin-1"));

        assertEquals(409, error.getStatusCode().value());
        assertTrue(error.getReason().contains("预占"));
    }

    @Test
    void searchesAccountsWithoutCreatingCredits() {
        Map<String, Object> result = service.accounts("player@", 0, 20);

        assertEquals(1L, result.get("total"));
        @SuppressWarnings("unchecked")
        var rows = (java.util.List<Map<String, Object>>) result.get("rows");
        assertEquals("player@example.com", rows.getFirst().get("email"));
        assertEquals(false, rows.getFirst().get("ledgerInitialized"));
        assertEquals(20, rows.getFirst().get("granted"));
        assertEquals(17, rows.getFirst().get("available"));
        assertEquals(3, rows.getFirst().get("consumed"));
        assertEquals(0, jdbc.queryForObject("select count(*) from turn_credit_grant", Integer.class));
    }
}

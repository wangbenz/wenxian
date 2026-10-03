package com.wenxian.admin.point;

import com.wenxian.admin.audit.AdminOperationLogRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PointRuleApplicationServiceTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private PointRuleApplicationService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("point-rule-" + UUID.randomUUID()).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table gameplay_config(config_key varchar(64) primary key,config_value varchar(255) not null,updated_at timestamp not null)");
        jdbc.execute("create table app_user(id varchar(64) primary key)");
        jdbc.execute("""
                create table turn_credit_grant(
                    id varchar(80) primary key,user_id varchar(64) not null,grant_type varchar(32) not null,
                    granted_count integer not null,available_count integer not null,reserved_count integer not null,
                    consumed_count integer not null,status varchar(24) not null,expires_at timestamp)
                """);
        jdbc.execute("create table admin_operation_log(id varchar(80) primary key,operated_at timestamp not null,operation varchar(64) not null,detail varchar(1000) not null,subject_user_id varchar(64))");
        Instant now = Instant.now();
        jdbc.update("insert into gameplay_config values ('account_public_turn_limit','20',?)", Timestamp.from(now));
        jdbc.update("insert into gameplay_config values ('account_public_turn_limit_message','旧提示',?)", Timestamp.from(now));
        jdbc.update("insert into gameplay_config values ('custom_api_enabled','true',?)", Timestamp.from(now));
        jdbc.update("insert into app_user values ('user-1')");
        jdbc.update("insert into app_user values ('user-2')");
        jdbc.update("insert into turn_credit_grant values ('opening-1','user-1','LEGACY_OPENING',20,12,3,5,'ACTIVE',null)");
        service = new PointRuleApplicationService(new JdbcPointRuleAdminRepository(jdbc),
                new AdminOperationLogRepository(jdbc));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void readsRuleAndLedgerSummary() {
        PointRuleView rule = service.rule();

        assertEquals(20, rule.openingGrant());
        assertEquals(2, rule.totalAccounts());
        assertEquals(1, rule.initializedAccounts());
        assertEquals(1, rule.awaitingInitializationAccounts());
        assertEquals(12, rule.availablePoints());
        assertEquals(3, rule.reservedPoints());
        assertEquals(5, rule.consumedPoints());
    }

    @Test
    void updatesOnlyOpeningRuleWithoutRebalancingEstablishedGrant() {
        PointRuleView updated = service.update(60, "推演点用完了，请切换自备服务。", "admin-1");

        assertEquals(60, updated.openingGrant());
        assertEquals("true", config("custom_api_enabled"));
        assertEquals(12, jdbc.queryForObject(
                "select available_count from turn_credit_grant where id='opening-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from admin_operation_log where operation='UPDATE_POINT_RULE'", Integer.class));
    }

    @Test
    void rejectsInvalidRuleBeforeWritingAnything() {
        assertThrows(ResponseStatusException.class, () -> service.update(0, "提示", "admin-1"));
        assertThrows(ResponseStatusException.class, () -> service.update(20, "   ", "admin-1"));

        assertEquals("20", config("account_public_turn_limit"));
        assertEquals(0, jdbc.queryForObject("select count(*) from admin_operation_log", Integer.class));
    }

    private String config(String key) {
        return jdbc.queryForObject("select config_value from gameplay_config where config_key=?", String.class, key);
    }
}

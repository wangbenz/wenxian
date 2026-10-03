package com.wenxian.admin.byok;

import com.wenxian.admin.audit.AdminOperationLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ByokRuleApplicationServiceTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private ByokRuleApplicationService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("byok-rule-" + UUID.randomUUID()).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table gameplay_config(config_key varchar(64) primary key,config_value varchar(255) not null,updated_at timestamp not null)");
        jdbc.execute("create table user_ai_provider_credential(user_id varchar(64),status varchar(24))");
        jdbc.execute("create table story_turn(id varchar(64),credential_source varchar(24),created_at timestamp)");
        jdbc.execute("create table admin_operation_log(id varchar(80) primary key,operated_at timestamp not null,operation varchar(64) not null,detail varchar(1000) not null,subject_user_id varchar(64))");
        Instant now = Instant.now();
        jdbc.update("insert into gameplay_config values ('custom_api_enabled','true',?)", Timestamp.from(now));
        jdbc.update("insert into gameplay_config values ('max_active_saves','4',?)", Timestamp.from(now));
        jdbc.update("insert into user_ai_provider_credential values ('user-1','ACTIVE')");
        jdbc.update("insert into user_ai_provider_credential values ('user-2','INVALID')");
        jdbc.update("insert into story_turn values ('turn-1','USER',?)", Timestamp.from(now));
        service = new ByokRuleApplicationService(jdbc, new AdminOperationLogRepository(jdbc));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void updatesOnlyGlobalSwitchAndReturnsNoCredentialMaterial() {
        var updated = service.update(false, "admin-1");

        assertFalse(updated.enabled());
        assertEquals(1, updated.activeCredentialUsers());
        assertEquals(1, updated.invalidCredentialUsers());
        assertEquals("4", config("max_active_saves"));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from admin_operation_log where operation='UPDATE_BYOK_RULE'", Integer.class));
    }

    private String config(String key) {
        return jdbc.queryForObject("select config_value from gameplay_config where config_key=?", String.class, key);
    }
}

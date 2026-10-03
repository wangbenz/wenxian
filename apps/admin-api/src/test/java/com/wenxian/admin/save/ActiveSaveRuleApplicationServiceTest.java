package com.wenxian.admin.save;

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

class ActiveSaveRuleApplicationServiceTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private ActiveSaveRuleApplicationService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("active-save-rule-" + UUID.randomUUID()).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table gameplay_config(config_key varchar(64) primary key,config_value varchar(255) not null,updated_at timestamp not null)");
        jdbc.execute("create table story_save(id varchar(64) primary key,user_id varchar(64),active_slot_no integer,deleted_at timestamp)");
        jdbc.execute("create table admin_operation_log(id varchar(80) primary key,operated_at timestamp not null,operation varchar(64) not null,detail varchar(1000) not null,subject_user_id varchar(64))");
        jdbc.update("insert into gameplay_config values ('max_active_saves','4',?)", Timestamp.from(Instant.now()));
        jdbc.update("insert into story_save values ('save-1','user-1',1,null)");
        jdbc.update("insert into story_save values ('save-2','user-1',2,null)");
        service = new ActiveSaveRuleApplicationService(jdbc, new AdminOperationLogRepository(jdbc));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void rejectsLoweringBelowExistingAccountWithoutChangingData() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.update(1, "admin-1"));

        assertEquals(409, error.getStatusCode().value());
        assertEquals("4", config());
        assertEquals(2, jdbc.queryForObject("select count(*) from story_save", Integer.class));
    }

    @Test
    void updatesRuleWithoutTouchingExistingSaves() {
        var updated = service.update(3, "admin-1");

        assertEquals(3, updated.maximumActiveSaves());
        assertEquals(2, updated.activeSaveCount());
        assertEquals(1, updated.usersWithMultipleSaves());
        assertEquals(2, jdbc.queryForObject("select count(*) from story_save", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from admin_operation_log where operation='UPDATE_ACTIVE_SAVE_RULE'", Integer.class));
    }

    private String config() {
        return jdbc.queryForObject("select config_value from gameplay_config where config_key='max_active_saves'", String.class);
    }
}

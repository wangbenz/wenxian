package com.wenxian.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminServiceOperationErrorTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private AdminService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("admin-errors-" + UUID.randomUUID()).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table app_user(id varchar(64) primary key, email varchar(255) not null)");
        jdbc.execute("create table story_save(id varchar(80) primary key, user_id varchar(64), source_save_id varchar(80), state_json clob)");
        jdbc.execute("""
                create table operation_error(
                  reference_id varchar(40) primary key, save_id varchar(80), operation varchar(120),
                  category varchar(32), error_code varchar(64), http_status integer,
                  public_message varchar(600), diagnostic clob, created_at timestamp)
                """);
        jdbc.update("insert into app_user(id,email) values ('user-1','player@example.com')");
        jdbc.update("insert into story_save(id,user_id,state_json) values ('save-1','user-1','{\"player\":{\"name\":\"林渊\"}}')");
        jdbc.update("""
                insert into operation_error(reference_id,save_id,operation,category,error_code,http_status,public_message,diagnostic,created_at)
                values ('WX-20260802-ABC123','save-1','POST /turns','RESOURCE','CURRENCY_INSUFFICIENT',422,'身上钱财不足','wallet mismatch',current_timestamp)
                """);
        service = new AdminService(jdbc);
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    @Test
    void findsProblemByReferenceOrPlayerAndCategory() {
        List<Map<String, Object>> byReference = service.operationErrors("ABC123", "", 100);
        assertEquals(1, byReference.size());
        assertEquals("player@example.com", byReference.getFirst().get("email"));
        assertEquals("林渊", byReference.getFirst().get("playerName"));

        assertEquals(1, service.operationErrors("player@example.com", "RESOURCE", 100).size());
        assertEquals(0, service.operationErrors("player@example.com", "SYSTEM", 100).size());

        List<Map<String, Object>> summary = service.operationErrorSummary(7, 10);
        assertEquals(1, summary.size());
        assertEquals("CURRENCY_INSUFFICIENT", summary.getFirst().get("code"));
        assertEquals(1L, summary.getFirst().get("affectedPlayers"));
    }

    @Test
    void resolvesLegacyErrorThroughUnifiedSaveSourceId() {
        jdbc.update("update story_save set id='story-save-1', source_save_id='save-1' where id='save-1'");

        List<Map<String, Object>> rows = service.operationErrors("player@example.com", "RESOURCE", 100);
        assertEquals(1, rows.size());
        assertEquals("林渊", rows.getFirst().get("playerName"));

        List<Map<String, Object>> summary = service.operationErrorSummary(7, 10);
        assertEquals(1L, summary.getFirst().get("affectedPlayers"));
    }
}

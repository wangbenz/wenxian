package com.wenxian.admin.history;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminHistoryApplicationServiceTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private AdminHistoryApplicationService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("admin-history-" + UUID.randomUUID()).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table app_user(id varchar(64) primary key,email varchar(255) not null)");
        jdbc.execute("create table story_save(id varchar(80) primary key,user_id varchar(64),state_json clob)");
        jdbc.execute("""
                create table operation_error(reference_id varchar(40) primary key,save_id varchar(80),
                  operation varchar(120),category varchar(32),error_code varchar(64),http_status integer,
                  public_message varchar(600),diagnostic clob,created_at timestamp)
                """);
        jdbc.execute("""
                create table admin_operation_log(id varchar(64) primary key,operated_at timestamp,
                  operation varchar(64),detail clob)
                """);
        jdbc.update("insert into app_user(id,email) values ('user-1','player@example.com')");
        jdbc.update("""
                insert into story_save(id,user_id,state_json) values
                ('save-1','user-1','{"trustedProjection":{"creationContract":{"identity":{"protagonistName":"林渊"}}}}')
                """);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        for (int index = 0; index < 135; index++) {
            Instant at = start.plus(index, ChronoUnit.DAYS);
            jdbc.update("""
                    insert into operation_error(reference_id,save_id,operation,category,error_code,http_status,
                      public_message,diagnostic,created_at) values (?,?,?,?,?,?,?,?,?)
                    """, "WX-" + String.format("%03d", index), "save-1", "POST /turns",
                    index % 2 == 0 ? "SYSTEM" : "STATE", "E_" + index, 500,
                    "失败 " + index, "diagnostic " + index, Timestamp.from(at));
            jdbc.update("""
                    insert into admin_operation_log(id,operated_at,operation,detail) values (?,?,?,?)
                    """, "log-" + String.format("%03d", index), Timestamp.from(at),
                    index % 2 == 0 ? "UPDATE_RULE" : "LOGIN", "操作 " + index);
        }
        service = new AdminHistoryApplicationService(new JdbcAdminHistoryRepository(jdbc));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void pagesAllProblemHistoryInsteadOfTruncatingAtOneHundred() {
        var page = service.problems("player@example.com", "SYSTEM", "", "", 2, 20);
        assertEquals(68, page.total());
        assertEquals(20, page.rows().size());
        assertEquals("林渊", page.rows().getFirst().playerName());
        assertEquals(2, page.page());
    }

    @Test
    void filtersAuditHistoryByOperationAndDate() {
        var page = service.auditLogs("操作", "UPDATE_RULE", "2026-02-01T00:00:00Z",
                "2026-02-10T23:59:59Z", 0, 100);
        assertEquals(5, page.total());
        assertEquals(5, page.rows().size());
        assertEquals("UPDATE_RULE", service.auditOperations().get(1));
    }

    @Test
    void rejectsInvertedDateRangeAndUnboundedPageSize() {
        assertThrows(ResponseStatusException.class, () -> service.problems(
                "", "", "2026-03-02T00:00:00Z", "2026-03-01T00:00:00Z", 0, 20));
        assertThrows(ResponseStatusException.class, () -> service.auditLogs("", "", "", "", 0, 101));
    }
}

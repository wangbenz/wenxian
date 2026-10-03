package com.wenxian.admin.byok;

import com.wenxian.admin.byok.ByokAdminViews.ConversionView;
import com.wenxian.admin.byok.ByokAdminViews.CredentialPageView;
import com.wenxian.admin.byok.ByokAdminViews.CredentialSummaryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ByokQueryServiceTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private ByokQueryService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("byok-query-" + UUID.randomUUID() + ";MODE=MySQL").build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table app_user(id varchar(64) primary key,email varchar(255),registered_at timestamp)");
        jdbc.execute("create table story_save(id varchar(80) primary key,user_id varchar(64),created_at timestamp)");
        jdbc.execute("create table story_turn(id varchar(80) primary key,save_id varchar(80),provider varchar(32),credential_source varchar(20),created_at timestamp)");
        jdbc.execute("create table analytics_event(id varchar(80),event_type varchar(48),user_id varchar(64),data_origin varchar(16),occurred_at timestamp)");
        jdbc.execute("create table ai_provider_config(provider_code varchar(32),display_name varchar(80))");
        jdbc.execute("create table user_model_preference(user_id varchar(64),provider_code varchar(32),model_code varchar(80),credential_source varchar(20))");
        jdbc.execute("""
                create table user_ai_provider_credential(
                    user_id varchar(64),provider_code varchar(32),api_key_ciphertext clob,masked_suffix varchar(8),
                    status varchar(24),last_verified_at timestamp,last_error_code varchar(48),created_at timestamp,updated_at timestamp)
                """);
        jdbc.execute("create table ai_usage_log(id varchar(80),save_id varchar(80),provider varchar(32),credential_source varchar(20),status varchar(20),created_at timestamp)");
        insertFacts();
        Clock clock = Clock.fixed(Instant.parse("2026-08-27T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
        service = new ByokQueryService(new JdbcByokQueryRepository(jdbc), clock);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void keepsCohortConversionNestedAndSeparatesImmatureUsers() {
        ConversionView view = service.conversion(LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-08-27"), 14);

        assertEquals(1, view.immatureUsers());
        assertEquals(2, view.stages().get(0).users());
        assertEquals(2, view.stages().get(1).users());
        assertEquals(1, view.stages().get(2).users());
        assertEquals(1, view.stages().get(3).users());
        assertEquals(1, view.stages().get(4).users());
    }

    @Test
    void countsCredentialRecordsAndPlayersSeparatelyWithoutReturningCiphertext() {
        CredentialSummaryView summary = service.credentialSummary(LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-08-27"));
        assertEquals(2, summary.snapshot().credentialUsers());
        assertEquals(3, summary.snapshot().credentialCount());
        assertEquals(2, summary.snapshot().activeCredentials());
        assertEquals(1, summary.snapshot().invalidCredentials());
        assertEquals(1, summary.snapshot().multiProviderUsers());

        CredentialPageView page = service.credentials("", "DEEPSEEK", "INVALID", false,
                0, 20, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-27"));
        assertEquals(1, page.total());
        assertEquals("1234", page.rows().getFirst().maskedSuffix());
        assertFalse(page.rows().getFirst().toString().contains("encrypted-secret"));
    }

    private void insertFacts() {
        insertUser("user-1", "one@example.com", "2026-08-01T08:00:00Z");
        insertUser("user-2", "two@example.com", "2026-08-02T08:00:00Z");
        insertUser("user-3", "new@example.com", "2026-08-20T08:00:00Z");
        jdbc.update("insert into story_save values ('save-1','user-1',?)", time("2026-08-02T08:00:00Z"));
        jdbc.update("insert into story_save values ('save-2','user-2',?)", time("2026-08-03T08:00:00Z"));
        jdbc.update("insert into story_turn values ('turn-1','save-1','MINIMAX','PUBLIC',?)", time("2026-08-03T08:00:00Z"));
        jdbc.update("insert into story_turn values ('turn-2','save-1','MINIMAX','USER',?)", time("2026-08-05T08:00:00Z"));
        jdbc.update("insert into analytics_event values ('event-1','BYOK_CREDENTIAL_CONNECTED','user-1','REALTIME',?)", time("2026-08-04T08:00:00Z"));
        jdbc.update("insert into ai_provider_config values ('MINIMAX','MiniMax'),('DEEPSEEK','DeepSeek')");
        jdbc.update("insert into user_model_preference values ('user-1','MINIMAX','minimax-m3','USER')");
        insertCredential("user-1", "MINIMAX", "ACTIVE", "5678", null, "2026-08-04T08:00:00Z");
        insertCredential("user-1", "DEEPSEEK", "INVALID", "1234", "AUTHENTICATION_FAILED", "2026-08-06T08:00:00Z");
        insertCredential("user-2", "MINIMAX", "ACTIVE", "9012", null, "2026-08-07T08:00:00Z");
        jdbc.update("insert into ai_usage_log values ('usage-1','save-1','DEEPSEEK','USER','FAILED',?)", time("2026-08-08T08:00:00Z"));
    }

    private void insertUser(String id, String email, String registeredAt) {
        jdbc.update("insert into app_user values (?,?,?)", id, email, time(registeredAt));
    }

    private void insertCredential(String userId, String provider, String status, String suffix,
                                  String errorCode, String createdAt) {
        Timestamp timestamp = time(createdAt);
        jdbc.update("insert into user_ai_provider_credential values (?,?,?,?,?,?,?,?,?)",
                userId, provider, "encrypted-secret", suffix, status, timestamp, errorCode, timestamp, timestamp);
    }

    private Timestamp time(String value) {
        return Timestamp.from(Instant.parse(value));
    }
}

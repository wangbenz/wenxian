package com.wenxian.admin;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminServiceV014OverviewTest {
    @Test
    void reportsNewEditionCredentialsCorrectionsAndMigrationGaps() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.execute("create table v014_game_save(id varchar(80) primary key, user_id varchar(80), status varchar(20))");
            jdbc.execute("""
                    create table v014_game_turn(
                        save_id varchar(80), turn_number int, player_action varchar(1000),
                        provider varchar(32), model varchar(80), credential_source varchar(20),
                        prompt_tokens bigint, completion_tokens bigint, latency_ms bigint, created_at timestamp)
                    """);
            jdbc.execute("create table user_ai_provider_credential(user_id varchar(80), provider_code varchar(32), status varchar(20))");
            jdbc.execute("create table user_ai_credential(user_id varchar(80), provider varchar(32), status varchar(20))");
            jdbc.execute("create table v014_state_correction(id varchar(80))");
            Instant now = Instant.now();
            jdbc.update("insert into v014_game_save values ('save-1','u1','ACTIVE')");
            jdbc.update("insert into v014_game_turn values ('save-1',1,'出门游历','DEEPSEEK','flash','PUBLIC',100,50,1200,?)",
                    Timestamp.from(now));
            jdbc.update("insert into user_ai_credential values ('u1','DEEPSEEK','ACTIVE')");
            jdbc.update("insert into user_ai_provider_credential values ('u1','DEEPSEEK','ACTIVE')");
            jdbc.update("insert into user_ai_provider_credential values ('u2','DEEPSEEK','INVALID')");
            jdbc.update("insert into v014_state_correction values ('correction-1')");

            var overview = new AdminService(jdbc).v014Overview();

            assertEquals(1, ((Number) overview.get("personalCredentialUsers")).intValue());
            assertEquals(1, ((Number) overview.get("invalidCredentialUsers")).intValue());
            assertEquals(1, ((Number) overview.get("manualCorrections")).intValue());
            assertEquals(0, ((Number) overview.get("legacyCredentialMigrationGaps")).intValue());
        } finally {
            database.shutdown();
        }
    }
}

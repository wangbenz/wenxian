package com.wenxian.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 保护管理台只读模型能够解释与 game-api 相同的 v2 命卷样例。 */
class StoryStateV2ContractTest {
    private static final String FIXTURE = "/contracts/story-state/story-state-v2.json";

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("admin-story-contract-" + UUID.randomUUID()).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("""
                create table app_user(
                    id varchar(64) primary key,email varchar(255),status varchar(24),
                    registered_at timestamp,last_login_at timestamp
                )
                """);
        jdbc.execute("""
                create table story_save(
                    id varchar(80) primary key,user_id varchar(64),status varchar(24),
                    state_json clob,turn_number integer,updated_at timestamp
                )
                """);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void sharedFixtureProducesTheSamePlayerAndRealmProjection() throws Exception {
        Timestamp now = Timestamp.from(Instant.parse("2026-08-29T00:00:00Z"));
        jdbc.update("insert into app_user values ('user-contract','contract@example.com','ACTIVE',?,?)", now, now);
        jdbc.update("insert into story_save values ('story-contract-v2','user-contract','ACTIVE',?,0,?)",
                fixture(), now);

        Map<String, Object> row = new AdminService(jdbc).currentTurnRanking(10).getFirst();

        assertEquals("沈砚", row.get("playerName"));
        assertEquals("炼气初期", row.get("realm"));
        assertEquals(0, row.get("turn"));
    }

    private String fixture() throws Exception {
        try (InputStream input = StoryStateV2ContractTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(input, "共享 Story v2 fixture 必须进入测试 classpath");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

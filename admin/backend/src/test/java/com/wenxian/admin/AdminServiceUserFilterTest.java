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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminServiceUserFilterTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private AdminService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("admin-user-filter-" + UUID.randomUUID())
                .build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("""
                create table app_user (
                    id varchar(64) primary key,
                    email varchar(255) not null,
                    status varchar(32) not null,
                    registered_at timestamp not null,
                    last_login_at timestamp
                )
                """);
        jdbc.execute("""
                create table story_save (
                    id varchar(80) primary key,
                    user_id varchar(64) not null,
                    slot_no integer not null default 1,
                    status varchar(24) not null,
                    state_json clob not null,
                    turn_number integer not null default 0,
                    updated_at timestamp
                )
                """);
        jdbc.execute("""
                create table auth_registration (
                    id varchar(64) primary key,
                    user_id varchar(64) not null,
                    ip_hash varchar(128) not null,
                    created_at timestamp not null
                )
                """);
        jdbc.execute("""
                create table user_ai_credential (
                    user_id varchar(64) primary key,
                    masked_suffix varchar(16),
                    status varchar(32),
                    last_verified_at timestamp
                )
                """);
        jdbc.execute("""
                create table story_turn (
                    id varchar(80) primary key,
                    save_id varchar(80) not null,
                    request_id varchar(80) not null,
                    turn_number integer not null,
                    player_action clob,
                    state_patch_json clob,
                    model varchar(120),
                    latency_ms bigint not null default 0,
                    created_at timestamp not null default current_timestamp,
                    prompt_tokens integer not null default 0,
                    completion_tokens integer not null default 0
                )
                """);
        service = new AdminService(jdbc);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void filtersPlayersByShanghaiRegistrationDate() {
        insertUser("user-today", "today@example.com", "2026-07-30", 10);
        insertUser("user-yesterday", "yesterday@example.com", "2026-07-29", 23);

        List<Map<String, Object>> result = service.users("", "", "", "", "2026-07-30", 0, 20);

        assertEquals(1, result.getFirst().get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get(1).get("rows");
        assertEquals("user-today", rows.getFirst().get("userId"));
    }

    @Test
    void usesFirstRegistrationEventWhenLoginHasMovedLegacyTimestamp() {
        insertUser("user-returning", "returning@example.com", "2026-07-30", 10);
        jdbc.update("""
                insert into auth_registration(id, user_id, ip_hash, created_at)
                values ('registration-returning', 'user-returning', 'test-ip', ?)
                """, shanghaiTimestamp("2026-07-29", 9));

        List<Map<String, Object>> wrongDay = service.users("", "", "", "", "2026-07-30", 0, 20);
        assertEquals(0, wrongDay.getFirst().get("total"));

        List<Map<String, Object>> trueDay = service.users("", "", "", "", "2026-07-29", 0, 20);
        assertEquals(1, trueDay.getFirst().get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) trueDay.get(1).get("rows");
        assertEquals("2026-07-29T01:00:00Z", rows.getFirst().get("registeredAt"));
    }

    @Test
    void rejectsInvalidRegistrationDate() {
        assertThrows(ResponseStatusException.class,
                () -> service.users("", "", "", "", "07-30-2026", 0, 20));
    }

    @Test
    void requiresMilestoneToBeReachedWithinOneCharacter() {
        insertUser("user-split", "split@example.com", "2026-07-30", 10);
        insertUser("user-reached", "reached@example.com", "2026-07-30", 11);
        insertSave("save-current", "user-split", "ACTIVE", 2, "{}");
        insertSave("save-old", "user-split", "ARCHIVED", 2, "{}");
        insertSave("save-three", "user-reached", "DEAD", 3, "{}");

        assertEquals(2, service.usersReachingTurnMilestone(1));
        assertEquals(1, service.usersReachingTurnMilestone(3));
        assertEquals(0, service.usersReachingTurnMilestone(5));
    }

    @Test
    void ranksCurrentCharactersByTheirOwnTurnCount() {
        insertUser("user-low", "low@example.com", "2026-07-30", 10);
        insertUser("user-high", "high@example.com", "2026-07-30", 11);
        jdbc.update("""
                insert into story_save(id,user_id,status,state_json,turn_number,updated_at)
                values ('save-low','user-low','ACTIVE','{"turn":3,"player":{"name":"低回合","realm":"凡俗"}}',3,current_timestamp)
                """);
        jdbc.update("""
                insert into story_save(id,user_id,status,state_json,turn_number,updated_at)
                values ('save-high','user-high','ACTIVE','{"turnNumber":18,"protagonist":{"name":"高回合","realm":"引气","race":"人族"}}',18,current_timestamp)
                """);
        jdbc.update("""
                insert into story_save(id,user_id,status,state_json,turn_number,updated_at)
                values ('save-archived','user-low','ARCHIVED','{"turn":999,"player":{"name":"封存命卷","realm":"化神"}}',999,current_timestamp)
                """);

        List<Map<String, Object>> ranking = service.currentTurnRanking(10);

        assertEquals(2, ranking.size());
        assertEquals("user-high", ranking.getFirst().get("userId"));
        assertEquals(18, ranking.getFirst().get("turn"));
        assertEquals("高回合", ranking.getFirst().get("playerName"));
        assertEquals("引气", ranking.getFirst().get("realm"));

        List<Map<String, Object>> users = service.users("", "", "", "", "", 0, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) users.get(1).get("rows");
        Map<String, Object> canonical = rows.stream()
                .filter(row -> "user-high".equals(row.get("userId")))
                .findFirst().orElseThrow();
        assertEquals("高回合", canonical.get("playerName"));
        assertEquals("引气", canonical.get("realm"));
        assertEquals("人族", canonical.get("race"));
    }

    private void insertUser(String id, String email, String date, int hour) {
        Timestamp registeredAt = shanghaiTimestamp(date, hour);
        jdbc.update("""
                insert into app_user(id, email, status, registered_at, last_login_at)
                values (?, ?, 'ACTIVE', ?, ?)
                """, id, email, registeredAt, registeredAt);
    }

    private Timestamp shanghaiTimestamp(String date, int hour) {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        return Timestamp.from(LocalDate.parse(date).atTime(hour, 0)
                .atZone(shanghai).toInstant());
    }

    private void insertSave(String id, String userId, String status, int turns, String state) {
        jdbc.update("insert into story_save(id,user_id,status,state_json,turn_number,updated_at) values (?,?,?,?,?,current_timestamp)",
                id, userId, status, state, turns);
    }
}

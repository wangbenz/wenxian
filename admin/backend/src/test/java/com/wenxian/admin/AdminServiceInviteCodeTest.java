package com.wenxian.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminServiceInviteCodeTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private AdminService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("admin-invites-" + UUID.randomUUID())
                .build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("""
                create table invite_code (
                    id varchar(64) primary key,
                    code_hash varchar(128) not null unique,
                    status varchar(32) not null,
                    max_uses integer not null,
                    used_count integer not null,
                    expires_at timestamp,
                    created_at timestamp not null
                )
                """);
        jdbc.execute("""
                create table admin_invite_code (
                    id varchar(64) primary key,
                    invite_code_id varchar(64) not null unique,
                    code_label varchar(32) not null,
                    created_at timestamp not null,
                    constraint fk_test_invite foreign key (invite_code_id) references invite_code(id)
                )
                """);
        jdbc.execute("""
                create table admin_operation_log (
                    id varchar(64) primary key,
                    operated_at timestamp not null,
                    operation varchar(64) not null,
                    detail varchar(1000) not null
                )
                """);
        service = new AdminService(jdbc);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 50})
    void generatesExactUsableCodesForWholeBatch(int count) {
        List<String> codes = service.generateInviteCodes(count, 20);

        assertEquals(count, codes.size());
        assertEquals(count, new HashSet<>(codes).size());
        assertTrue(codes.stream().allMatch(code -> code.matches("^WX-[A-HJ-NP-Z2-9]{10}$")));
        assertEquals(count, jdbc.queryForObject("select count(*) from invite_code", Integer.class));
        assertEquals(count, jdbc.queryForObject("select count(*) from admin_invite_code", Integer.class));

        for (String code : codes) {
            Integer matches = jdbc.queryForObject(
                    "select count(*) from invite_code where code_hash = ? and status = 'ACTIVE' and used_count = 0",
                    Integer.class, AdminService.sha256(code));
            assertEquals(1, matches);
        }
    }

    @Test
    void doesNotFabricatePlaintextForLegacyHashOnlyCode() {
        Instant now = Instant.now();
        jdbc.update("""
                insert into invite_code(id, code_hash, status, max_uses, used_count, expires_at, created_at)
                values (?, ?, 'ACTIVE', 20, 0, null, ?)
                """, "invite-legacy", AdminService.sha256("REAL-SECRET-CODE"), Timestamp.from(now));

        List<Map<String, Object>> codes = service.inviteCodes("");

        assertEquals(1, codes.size());
        assertNull(codes.getFirst().get("codeDisplay"));
        assertFalse((Boolean) codes.getFirst().get("plaintextAvailable"));
    }

    @Test
    void deletesOnlyCodesThatCanNoLongerBeUsed() {
        List<String> codes = service.generateInviteCodes(4, 2);
        jdbc.update("update invite_code set status = 'EXHAUSTED', used_count = 2 where code_hash = ?",
                AdminService.sha256(codes.get(0)));
        jdbc.update("update invite_code set used_count = 2 where code_hash = ?",
                AdminService.sha256(codes.get(1)));
        jdbc.update("update invite_code set expires_at = ? where code_hash = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), AdminService.sha256(codes.get(2)));

        int deleted = service.deleteUnusableInviteCodes();

        assertEquals(3, deleted);
        assertEquals(1, jdbc.queryForObject("select count(*) from invite_code", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from admin_invite_code", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from invite_code where code_hash = ? and status = 'ACTIVE' and used_count < max_uses",
                Integer.class, AdminService.sha256(codes.get(3))));
    }
}

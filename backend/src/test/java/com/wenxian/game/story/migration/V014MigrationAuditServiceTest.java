package com.wenxian.game.story.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.v014.model.V014State;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V014MigrationAuditServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void auditsEverySourceAndKeepsBrokenSaveAsAReportFinding() throws Exception {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            insertValid(jdbc, "v014-audit-valid");
            insertBroken(jdbc, "v014-audit-broken");
            V014MigrationSourceReader sources = new V014MigrationSourceReader(jdbc);
            V014MigrationPreviewService previews = new V014MigrationPreviewService(sources, mapper);
            Clock fixed = Clock.fixed(Instant.parse("2026-08-08T08:00:00Z"), ZoneOffset.UTC);

            var report = new V014MigrationAuditService(sources, previews, fixed).auditAll();

            assertEquals(2, report.sourceTotal());
            assertEquals(2, report.scanned());
            assertEquals(1, report.statusCounts().get("INVALID"));
            assertEquals(1, report.statusCounts().get("WARNING"));
            assertTrue(report.issueCounts().containsKey("STATE_JSON_INVALID"));
            assertEquals(1, report.totalTurns());
            assertEquals(Instant.parse("2026-08-08T08:00:00Z"), report.generatedAt());
            assertEquals(List.of("v014-audit-broken", "v014-audit-valid"),
                    report.items().stream().map(V014MigrationAuditModels.AuditItem::sourceSaveId).toList());
            assertTrue(report.items().stream().noneMatch(item -> item.error() != null && item.error().contains("{broken")));
            assertEquals(0, jdbc.queryForObject("select count(*) from story_save", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from story_save_migration", Integer.class));
        } finally {
            database.shutdown();
        }
    }

    private EmbeddedDatabase database() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Timestamp now = Timestamp.from(Instant.parse("2026-08-08T06:00:00Z"));
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                "audit-user", "audit@test.local", "ACTIVE", now, now);
        return database;
    }

    private void insertValid(JdbcTemplate jdbc, String saveId) throws Exception {
        V014State state = new V014State(14, saveId, 1, "ACTIVE",
                new V014State.WorldProfile("XUANXU", "玄墟界", "OFFICIAL", "仙路未明", "轻叙事", "青岐州"),
                new V014State.Protagonist("陆沉", "人族", "男", "山村少年", "引气", "问道", "自由探索"),
                60, "青岐山门", "气息平稳", "山门初见", "你来到青岐山门。",
                List.of(), List.of(), List.of(), List.of(), List.of());
        insertSave(jdbc, saveId, mapper.writeValueAsString(state), 1);
        Timestamp now = Timestamp.from(Instant.parse("2026-08-08T06:00:01Z"));
        jdbc.update("""
                insert into v014_game_turn(
                    id,save_id,request_id,turn_number,player_action,narrative_text,choices_json,memory_patch_json,
                    world_time_before,world_time_after,provider,model,credential_source,prompt_tokens,
                    completion_tokens,latency_ms,created_at
                ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, "turn-" + saveId, saveId, "request-1", 1, "进入山门", "你踏入山门。", "[]", "{}",
                0, 60, "DEEPSEEK", "flash", "PUBLIC", 100, 200, 3000, now);
    }

    private void insertBroken(JdbcTemplate jdbc, String saveId) {
        insertSave(jdbc, saveId, "{broken", 2);
    }

    private void insertSave(JdbcTemplate jdbc, String saveId, String stateJson, int slotNo) {
        Timestamp now = Timestamp.from(Instant.parse("2026-08-08T06:00:00Z"));
        jdbc.update("""
                insert into v014_game_save(
                    id,user_id,slot_no,world_code,state_schema_version,state_json,revision,status,created_at,updated_at
                ) values (?,?,?,?,?,?,?,?,?,?)
                """, saveId, "audit-user", slotNo, "XUANXU", 14, stateJson, 1, "ACTIVE", now, now);
    }
}

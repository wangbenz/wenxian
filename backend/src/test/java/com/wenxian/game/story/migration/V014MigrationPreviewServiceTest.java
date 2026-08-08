package com.wenxian.game.story.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.PreviewStatus;
import com.wenxian.game.v014.model.V014State;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V014MigrationPreviewServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void previewSplitsCoreEntitiesAndMemoriesWithoutWritingAnyTable() throws Exception {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            insertCompleteSource(jdbc, "v014-save-preview");
            Map<String, Integer> before = tableCounts(jdbc);
            String sourceJsonBefore = jdbc.queryForObject(
                    "select state_json from v014_game_save where id='v014-save-preview'", String.class);

            V014MigrationPreviewService service = service(jdbc);
            var first = service.preview("v014-save-preview");
            var second = service.preview("v014-save-preview");

            assertEquals(PreviewStatus.WARNING, first.status(), "旧快照无法还原实体首次出现回合，应显式警告");
            assertEquals(2, first.sourceTurnCount());
            assertEquals(2, first.targetTurnCount());
            assertEquals(Map.of("ITEM", 1, "ABILITY", 1, "CHARACTER", 1), first.entityCounts());
            assertEquals(1, first.memoryCount());
            assertEquals(1, first.correctionCount());
            assertTrue(first.conversationPresent());
            assertFalse(first.target().coreState().has("inventory"));
            assertFalse(first.target().coreState().has("abilities"));
            assertFalse(first.target().coreState().has("characters"));
            assertFalse(first.target().coreState().has("memories"));
            assertEquals("陆沉", first.target().coreState().path("protagonist").path("name").asText());
            assertEquals(3, first.target().entities().size());
            assertEquals(2, first.target().turnHashes().size());
            assertEquals(1, first.target().correctionHashes().size());
            assertEquals(2, first.target().memories().getFirst().occurredTurn());
            assertFalse(first.target().memories().getFirst().occurredTurnInferred());
            assertEquals(first.beforeHash(), second.beforeHash());
            assertEquals(first.afterHash(), second.afterHash());
            assertEquals(before, tableCounts(jdbc), "只读预演不得增删任何旧表或统一表记录");
            assertEquals(sourceJsonBefore, jdbc.queryForObject(
                    "select state_json from v014_game_save where id='v014-save-preview'", String.class));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void invalidSourceJsonProducesReportInsteadOfPartialTarget() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            insertSave(jdbc, "v014-save-broken", "{broken-json", 14, 1);
            Map<String, Integer> before = tableCounts(jdbc);

            var report = service(jdbc).preview("v014-save-broken");

            assertEquals(PreviewStatus.INVALID, report.status());
            assertTrue(report.issues().stream().anyMatch(issue -> "STATE_JSON_INVALID".equals(issue.code())));
            assertNotNull(report.beforeHash());
            assertNull(report.afterHash());
            assertNull(report.target());
            assertEquals(before, tableCounts(jdbc));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void invalidTurnPayloadAndBackwardTimeBlockMigration() throws Exception {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            V014State state = state("v014-save-turn-broken");
            insertSave(jdbc, state.saveId(), mapper.writeValueAsString(state), 14, 2);
            insertTurn(jdbc, state.saveId(), 1, 60, 30, "not-json", "{}");

            var report = service(jdbc).preview(state.saveId());

            assertEquals(PreviewStatus.INVALID, report.status());
            assertTrue(report.issues().stream().anyMatch(issue -> "TURN_TIME_INVALID".equals(issue.code())));
            assertTrue(report.issues().stream().anyMatch(issue -> "TURN_CHOICES_INVALID".equals(issue.code())));
            assertNotNull(report.target(), "预演仍应保留候选内容，方便管理员定位具体错误");
            assertEquals(0, jdbc.queryForObject("select count(*) from story_save", Integer.class));
        } finally {
            database.shutdown();
        }
    }

    private V014MigrationPreviewService service(JdbcTemplate jdbc) {
        return new V014MigrationPreviewService(new V014MigrationSourceReader(jdbc), mapper);
    }

    private EmbeddedDatabase database() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Timestamp now = Timestamp.from(Instant.parse("2026-08-08T06:00:00Z"));
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                "preview-user", "preview@test.local", "ACTIVE", now, now);
        return database;
    }

    private void insertCompleteSource(JdbcTemplate jdbc, String saveId) throws Exception {
        V014State state = state(saveId);
        insertSave(jdbc, saveId, mapper.writeValueAsString(state), 14, 3);
        insertTurn(jdbc, saveId, 1, 0, 60, "[]", "{}");
        insertTurn(jdbc, saveId, 2, 60, 120, "[]", "{\"location\":\"青岐山门\"}");
        Timestamp now = Timestamp.from(Instant.parse("2026-08-08T06:30:00Z"));
        jdbc.update("""
                insert into v014_conversation(save_id,rolling_summary,summarized_through_turn,updated_at)
                values (?,?,?,?)
                """, saveId, "陆沉已抵达青岐山门。", 2, now);
        jdbc.update("""
                insert into v014_state_correction(id,save_id,user_id,instruction_text,before_json,after_json,created_at)
                values (?,?,?,?,?,?,?)
                """, "v014-fix-preview", saveId, "preview-user", "修正令牌名称",
                "{\"name\":\"旧令\"}", "{\"name\":\"青纹令\"}", now);
    }

    private void insertSave(JdbcTemplate jdbc, String saveId, String stateJson,
                            int schemaVersion, long revision) {
        Timestamp now = Timestamp.from(Instant.parse("2026-08-08T06:00:00Z"));
        jdbc.update("""
                insert into v014_game_save(
                    id,user_id,slot_no,world_code,state_schema_version,state_json,revision,status,created_at,updated_at
                ) values (?,?,?,?,?,?,?,?,?,?)
                """, saveId, "preview-user", 1, "XUANXU", schemaVersion, stateJson, revision,
                "ACTIVE", now, now);
    }

    private void insertTurn(JdbcTemplate jdbc, String saveId, int turnNumber, long before, long after,
                            String choicesJson, String patchJson) {
        Timestamp now = Timestamp.from(Instant.parse("2026-08-08T06:00:00Z").plusSeconds(turnNumber));
        jdbc.update("""
                insert into v014_game_turn(
                    id,save_id,request_id,turn_number,player_action,narrative_text,choices_json,memory_patch_json,
                    world_time_before,world_time_after,provider,model,credential_source,prompt_tokens,
                    completion_tokens,latency_ms,created_at
                ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, "v014-turn-" + saveId + "-" + turnNumber, saveId, "request-" + turnNumber,
                turnNumber, "行动" + turnNumber, "剧情" + turnNumber, choicesJson, patchJson, before, after,
                "DEEPSEEK", "flash", "PUBLIC", 100, 200, 3000, now);
    }

    private V014State state(String saveId) {
        return new V014State(14, saveId, 2, "ACTIVE",
                new V014State.WorldProfile("XUANXU", "玄墟界", "OFFICIAL", "仙路未明", "轻叙事", "青岐州"),
                new V014State.Protagonist("陆沉", "人族", "男", "山村少年", "引气", "问道", "自由探索"),
                120, "青岐山门", "气息平稳", "山门初见", "你来到青岐山门。",
                List.of(new V014State.Choice("choice-1", "进入山门", "继续探查")),
                List.of(new V014State.Item("item-token", "青纹令", "信物", "一枚旧令牌",
                        "随身携带", "故人所赠", 1, true)),
                List.of(new V014State.Ability("ability-breath", "引气诀", "功法", "基础吐纳法",
                        "初学", "山门传承")),
                List.of(new V014State.Character("character-elder", "守门老者", "KNOWN", "山门守卫",
                        "一面之缘", "仍在山门", "青岐山门", true)),
                List.of(new V014State.Memory("memory-arrival", "抵达山门", "第一次看见修行宗门", 120, "KEY")));
    }

    private Map<String, Integer> tableCounts(JdbcTemplate jdbc) {
        List<String> tables = List.of("v014_game_save", "v014_game_turn", "v014_conversation",
                "v014_state_correction", "story_save", "story_turn", "story_entity", "story_memory",
                "story_conversation", "story_state_correction", "story_save_migration");
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : tables) counts.put(table, jdbc.queryForObject("select count(*) from " + table, Integer.class));
        return counts;
    }
}

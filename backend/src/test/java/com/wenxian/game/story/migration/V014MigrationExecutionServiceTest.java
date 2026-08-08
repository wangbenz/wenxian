package com.wenxian.game.story.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.migration.V014MigrationExecutionModels.ExecutionStage;
import com.wenxian.game.story.persistence.StorySaveRepository;
import com.wenxian.game.v014.model.V014State;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V014MigrationExecutionServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void prepareWritesAnIsolatedCandidateAndActivationRequiresASecondExplicitCall() throws Exception {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            String sourceSaveId = "v014-save-execution";
            insertSource(jdbc, sourceSaveId, "{}");
            String sourceBefore = jdbc.queryForObject(
                    "select state_json from v014_game_save where id=?", String.class, sourceSaveId);
            V014MigrationExecutionService service = service(jdbc);

            var prepared = inTransaction(database, () -> service.prepare(sourceSaveId));

            assertEquals(ExecutionStage.PREPARED, prepared.stage());
            assertEquals("MIGRATING", jdbc.queryForObject(
                    "select status from story_save where id=?", String.class, prepared.targetSaveId()));
            assertEquals("READY", jdbc.queryForObject(
                    "select status from story_save_migration where source_save_id=?", String.class, sourceSaveId));
            assertTrue(new StorySaveRepository(jdbc, mapper)
                    .findOwned("migration-user", prepared.targetSaveId()).isEmpty(),
                    "MIGRATING 候选不得进入玩家可玩命卷读取路径");
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from story_turn where save_id=?", Integer.class, prepared.targetSaveId()));
            assertEquals(3, jdbc.queryForObject(
                    "select count(*) from story_entity where save_id=?", Integer.class, prepared.targetSaveId()));
            assertEquals(sourceBefore, jdbc.queryForObject(
                    "select state_json from v014_game_save where id=?", String.class, sourceSaveId));
            assertEquals(ExecutionStage.ALREADY_PREPARED,
                    inTransaction(database, () -> service.prepare(sourceSaveId)).stage());

            var activated = inTransaction(database, () -> service.activate(sourceSaveId));

            assertEquals(ExecutionStage.ACTIVATED, activated.stage());
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "select status from story_save where id=?", String.class, activated.targetSaveId()));
            assertEquals("SUCCESS", jdbc.queryForObject(
                    "select status from story_save_migration where source_save_id=?", String.class, sourceSaveId));
            assertTrue(new StorySaveRepository(jdbc, mapper)
                    .findOwned("migration-user", activated.targetSaveId()).isPresent());
            assertEquals(ExecutionStage.ALREADY_ACTIVATED,
                    inTransaction(database, () -> service.activate(sourceSaveId)).stage());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void ambiguousHistoricalPatchIsQuarantinedAndStillPassesCandidateHashValidation() throws Exception {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            String sourceSaveId = "v014-save-quarantine-execution";
            insertSource(jdbc, sourceSaveId, "{\"title\",4:5}");

            var result = inTransaction(database, () -> service(jdbc).prepare(sourceSaveId));

            assertEquals(ExecutionStage.PREPARED, result.stage());
            assertTrue(result.warningCodes().contains("TURN_PATCH_QUARANTINED"));
            String storedPatch = jdbc.queryForObject("""
                    select state_patch_json from story_turn where save_id=? and turn_number=1
                    """, String.class, result.targetSaveId());
            assertEquals("QUARANTINED", mapper.readTree(storedPatch)
                    .path("_legacyMigration").path("status").asText());
            assertEquals("{\"title\",4:5}", jdbc.queryForObject("""
                    select memory_patch_json from v014_game_turn where save_id=? and turn_number=1
                    """, String.class, sourceSaveId));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void sourceChangeAfterPreparationBlocksActivationAndLeavesCandidateIsolated() throws Exception {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            String sourceSaveId = "v014-save-source-changed";
            insertSource(jdbc, sourceSaveId, "{}");
            V014MigrationExecutionService service = service(jdbc);
            var prepared = inTransaction(database, () -> service.prepare(sourceSaveId));
            jdbc.update("update v014_game_save set revision=revision+1 where id=?", sourceSaveId);

            var result = inTransaction(database, () -> service.activate(sourceSaveId));

            assertEquals(ExecutionStage.HASH_MISMATCH, result.stage());
            assertEquals("MIGRATING", jdbc.queryForObject(
                    "select status from story_save where id=?", String.class, prepared.targetSaveId()));
            assertEquals("HASH_MISMATCH", jdbc.queryForObject(
                    "select status from story_save_migration where source_save_id=?", String.class, sourceSaveId));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void candidateChangeAfterPreparationBlocksActivation() throws Exception {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            String sourceSaveId = "v014-save-target-changed";
            insertSource(jdbc, sourceSaveId, "{}");
            V014MigrationExecutionService service = service(jdbc);
            var prepared = inTransaction(database, () -> service.prepare(sourceSaveId));
            jdbc.update("update story_turn set narrative_text='被意外改写' where save_id=?",
                    prepared.targetSaveId());

            var result = inTransaction(database, () -> service.activate(sourceSaveId));

            assertEquals(ExecutionStage.HASH_MISMATCH, result.stage());
            assertEquals("MIGRATING", jdbc.queryForObject(
                    "select status from story_save where id=?", String.class, prepared.targetSaveId()));
            assertEquals("HASH_MISMATCH", jdbc.queryForObject(
                    "select status from story_save_migration where source_save_id=?", String.class, sourceSaveId));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void invalidPreviewAndTargetWriteFailureLeaveNoPartialMigrationRows() throws Exception {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            Timestamp now = Timestamp.from(Instant.parse("2026-08-08T06:00:00Z"));
            jdbc.update("""
                    insert into v014_game_save(
                        id,user_id,slot_no,world_code,state_schema_version,state_json,revision,status,created_at,updated_at
                    ) values (?,?,?,?,?,?,?,?,?,?)
                    """, "v014-invalid-execution", "migration-user", 1, "XUANXU", 14, "{broken", 1,
                    "ACTIVE", now, now);

            assertThrows(IllegalStateException.class, () -> inTransaction(database,
                    () -> service(jdbc).prepare("v014-invalid-execution")));
            assertEquals(0, jdbc.queryForObject("select count(*) from story_save", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from story_save_migration", Integer.class));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void targetConstraintFailureRollsBackTheAuditAndCandidateTogether() throws Exception {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            String sourceSaveId = "v014-save-slot-conflict";
            insertSource(jdbc, sourceSaveId, "{}");
            Timestamp now = Timestamp.from(Instant.parse("2026-08-08T06:00:00Z"));
            jdbc.update("""
                    insert into story_save(
                        id,user_id,slot_no,status,world_code,engine_code,engine_version,state_schema_version,
                        source_type,source_edition,source_save_id,parent_save_id,state_json,revision,turn_number,
                        created_release,last_played_release,created_at,updated_at
                    ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, "existing-story-save", "migration-user", 1, "ACTIVE", "XUANXU",
                    "LIGHT_NARRATIVE", 1, 1, "NATIVE", "V0.14", null, null, "{}", 1, 0,
                    "V0.14", "V0.14", now, now);

            assertThrows(RuntimeException.class,
                    () -> inTransaction(database, () -> service(jdbc).prepare(sourceSaveId)));

            assertEquals(1, jdbc.queryForObject("select count(*) from story_save", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from story_save_migration", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from story_turn", Integer.class));
        } finally {
            database.shutdown();
        }
    }

    private V014MigrationExecutionService service(JdbcTemplate jdbc) {
        V014MigrationPreviewService preview = new V014MigrationPreviewService(
                new V014MigrationSourceReader(jdbc), mapper);
        return new V014MigrationExecutionService(preview, new StoryMigrationRepository(jdbc, mapper), mapper);
    }

    private EmbeddedDatabase database() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Timestamp now = Timestamp.from(Instant.parse("2026-08-08T06:00:00Z"));
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                "migration-user", "migration@test.local", "ACTIVE", now, now);
        return database;
    }

    private void insertSource(JdbcTemplate jdbc, String saveId, String patch) throws Exception {
        Timestamp now = Timestamp.from(Instant.parse("2026-08-08T06:00:00Z"));
        V014State state = new V014State(14, saveId, 1, "ACTIVE",
                new V014State.WorldProfile("XUANXU", "玄墟界", "OFFICIAL", "仙路未明", "轻叙事", "青岐州"),
                new V014State.Protagonist("陆沉", "人族", "男", "山村少年", "引气", "问道", "自由探索"),
                60, "青岐山门", "气息平稳", "山门初见", "你来到青岐山门。",
                List.of(new V014State.Choice("choice-1", "进入山门", "继续探查")),
                List.of(new V014State.Item("item-token", "青纹令", "信物", "一枚旧令牌",
                        "随身携带", "故人所赠", 1, true)),
                List.of(new V014State.Ability("ability-breath", "引气诀", "功法", "基础吐纳法",
                        "初学", "山门传承")),
                List.of(new V014State.Character("character-elder", "守门老者", "KNOWN", "山门守卫",
                        "一面之缘", "仍在山门", "青岐山门", true)),
                List.of(new V014State.Memory("memory-arrival", "抵达山门", "第一次看见修行宗门", 60, "KEY")));
        jdbc.update("""
                insert into v014_game_save(
                    id,user_id,slot_no,world_code,state_schema_version,state_json,revision,status,created_at,updated_at
                ) values (?,?,?,?,?,?,?,?,?,?)
                """, saveId, "migration-user", 1, "XUANXU", 14, mapper.writeValueAsString(state), 2,
                "ACTIVE", now, now);
        jdbc.update("""
                insert into v014_game_turn(
                    id,save_id,request_id,turn_number,player_action,narrative_text,choices_json,memory_patch_json,
                    world_time_before,world_time_after,provider,model,credential_source,prompt_tokens,
                    completion_tokens,latency_ms,created_at
                ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, "turn-" + saveId, saveId, "request-1", 1, "进入山门", "你踏入山门。", "[]", patch,
                0, 60, "DEEPSEEK", "flash", "PUBLIC", 100, 200, 3000, now);
        jdbc.update("""
                insert into v014_conversation(save_id,rolling_summary,summarized_through_turn,updated_at)
                values (?,?,?,?)
                """, saveId, "陆沉已抵达青岐山门。", 1, now);
        jdbc.update("""
                insert into v014_state_correction(id,save_id,user_id,instruction_text,before_json,after_json,created_at)
                values (?,?,?,?,?,?,?)
                """, "correction-" + saveId, saveId, "migration-user", "修正令牌名称",
                "{\"name\":\"旧令\"}", "{\"name\":\"青纹令\"}", now);
    }

    private <T> T inTransaction(EmbeddedDatabase database, java.util.concurrent.Callable<T> action) {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
        return transaction.execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        });
    }
}

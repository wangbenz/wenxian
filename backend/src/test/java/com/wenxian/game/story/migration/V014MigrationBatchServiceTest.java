package com.wenxian.game.story.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.migration.V014MigrationBatchModels.ActivateRequest;
import com.wenxian.game.v014.model.V014State;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V014MigrationBatchServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void migrationWritesStayDisabledUntilEachPolicyIsExplicitlyEnabled() throws Exception {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            insertSource(jdbc, "v014-batch-a");
            insertSource(jdbc, "v014-batch-b");
            Fixture fixture = fixture(jdbc);

            var firstPage = fixture.batches.previewPage("", 1);
            assertEquals(1, firstPage.items().size());
            assertEquals("v014-batch-a", firstPage.items().getFirst().sourceSaveId());
            assertEquals("v014-batch-a", firstPage.nextCursor());
            assertEquals("UNPROCESSED", firstPage.items().getFirst().migrationStatus());
            assertFalse(fixture.policy.current().prepareEnabled());
            assertFalse(fixture.policy.current().activationEnabled());
            assertThrows(IllegalStateException.class,
                    () -> fixture.batches.prepareBatch(List.of("v014-batch-a")));

            fixture.policy.update(true, false, 2);
            var prepared = fixture.batches.prepareBatch(List.of("v014-batch-a", "v014-batch-b", "v014-batch-a"));
            assertEquals(2, prepared.requested(), "重复源 ID 应只准备一次");
            assertEquals(2, prepared.succeeded());
            assertEquals(2, jdbc.queryForObject(
                    "select count(*) from story_save where status='MIGRATING'", Integer.class));
            assertThrows(IllegalStateException.class, () -> fixture.batches.activateOne(new ActivateRequest(
                    prepared.items().getFirst().sourceSaveId(), prepared.items().getFirst().beforeHash(),
                    prepared.items().getFirst().afterHash())));

            fixture.policy.update(true, true, 2);
            var selected = prepared.items().getFirst();
            assertThrows(IllegalStateException.class, () -> fixture.batches.activateOne(new ActivateRequest(
                    selected.sourceSaveId(), "outdated", selected.afterHash())));
            var activated = fixture.batches.activateOne(new ActivateRequest(
                    selected.sourceSaveId(), selected.beforeHash(), selected.afterHash()));
            assertTrue(activated.success());
            assertEquals("ACTIVATED", activated.stage());
            assertEquals(1, fixture.batches.overview().statusCounts().get("SUCCESS"));
            assertEquals(1, fixture.batches.overview().statusCounts().get("READY"));
            assertEquals(0, fixture.batches.overview().unprocessed());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void invalidSourceAppearsInPreviewButCannotCreateCandidate() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            Timestamp now = Timestamp.from(Instant.parse("2026-08-08T06:00:00Z"));
            jdbc.update("""
                    insert into v014_game_save(
                        id,user_id,slot_no,world_code,state_schema_version,state_json,revision,status,created_at,updated_at
                    ) values (?,?,?,?,?,?,?,?,?,?)
                    """, "v014-broken", "batch-user", 1, "XUANXU", 14, "{broken", 1,
                    "ACTIVE", now, now);
            Fixture fixture = fixture(jdbc);

            var page = fixture.batches.previewPage("", 10);
            assertEquals("INVALID", page.items().getFirst().previewStatus());
            assertNull(page.nextCursor());
            fixture.policy.update(true, false, 10);
            var result = fixture.batches.prepareBatch(List.of("v014-broken"));
            assertEquals(1, result.failed());
            assertEquals(0, jdbc.queryForObject("select count(*) from story_save", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from story_save_migration", Integer.class));
        } finally {
            database.shutdown();
        }
    }

    private Fixture fixture(JdbcTemplate jdbc) {
        V014MigrationSourceReader sources = new V014MigrationSourceReader(jdbc);
        V014MigrationPreviewService previews = new V014MigrationPreviewService(sources, mapper);
        StoryMigrationRepository migrations = new StoryMigrationRepository(jdbc, mapper);
        V014MigrationExecutionService executions = new V014MigrationExecutionService(previews, migrations, mapper);
        StoryMigrationOperationPolicy policy = new StoryMigrationOperationPolicy(jdbc);
        return new Fixture(new V014MigrationBatchService(sources, previews, executions, migrations, policy), policy);
    }

    private EmbeddedDatabase database() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Timestamp now = Timestamp.from(Instant.parse("2026-08-08T06:00:00Z"));
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                "batch-user", "batch@test.local", "ACTIVE", now, now);
        return database;
    }

    private void insertSource(JdbcTemplate jdbc, String saveId) throws Exception {
        Timestamp now = Timestamp.from(Instant.parse("2026-08-08T06:00:00Z"));
        V014State state = new V014State(14, saveId, 1, "ACTIVE",
                new V014State.WorldProfile("XUANXU", "玄墟界", "OFFICIAL", "仙路未明", "轻叙事", "青岐州"),
                new V014State.Protagonist("陆沉", "人族", "男", "山村少年", "引气", "问道", "自由探索"),
                60, "青岐山门", "气息平稳", "山门初见", "你来到青岐山门。",
                List.of(), List.of(), List.of(), List.of(), List.of());
        jdbc.update("""
                insert into v014_game_save(
                    id,user_id,slot_no,world_code,state_schema_version,state_json,revision,status,created_at,updated_at
                ) values (?,?,?,?,?,?,?,?,?,?)
                """, saveId, "batch-user", saveId.endsWith("a") ? 1 : 2, "XUANXU", 14,
                mapper.writeValueAsString(state), 1, "ACTIVE", now, now);
        jdbc.update("""
                insert into v014_game_turn(
                    id,save_id,request_id,turn_number,player_action,narrative_text,choices_json,memory_patch_json,
                    world_time_before,world_time_after,provider,model,credential_source,prompt_tokens,
                    completion_tokens,latency_ms,created_at
                ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, "turn-" + saveId, saveId, "request-" + saveId, 1, "进入山门", "你踏入山门。",
                "[]", "{}", 0, 60, "DEEPSEEK", "flash", "PUBLIC", 100, 200, 3000, now);
    }

    private record Fixture(V014MigrationBatchService batches, StoryMigrationOperationPolicy policy) {}
}

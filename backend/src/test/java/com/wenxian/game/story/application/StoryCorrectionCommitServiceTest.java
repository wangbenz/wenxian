package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.story.agent.StoryCorrectionAgent;
import com.wenxian.game.story.domain.StoryCorrectionProposal;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.persistence.StoryCorrectionRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewSave;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import com.wenxian.game.story.persistence.StorySaveRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoryCorrectionCommitServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void commitsSnapshotAffectedEntityAndAuditWithoutAdvancingTurnOrTime() {
        EmbeddedDatabase database = database();
        try {
            TestFixture fixture = fixture(database, false);
            String token = fixture.tokens.issue("user-1", "story-save-1", 1,
                    "我现在应在北门，青纹令只剩两枚", proposal());

            StoryCorrectionCommitService.Committed committed = fixture.service.confirm(
                    "user-1", "story-save-1", 1, token);

            var saved = fixture.saves.findOwned("user-1", "story-save-1").orElseThrow();
            var projection = fixture.projections.load(saved, fixture.entities.listAll("story-save-1"),
                    fixture.memories.recentActive("story-save-1", 80));
            assertEquals(2, committed.revision());
            assertEquals(7, committed.turnNumber(), "修正不能增加回合");
            assertEquals(7, saved.turnNumber());
            assertEquals(120, projection.worldTimeMinutes(), "修正不能推进世界时间");
            assertEquals("青岐城北门", projection.coreState().location());
            assertEquals(2, projection.entities().get("item-token").quantity().value());
            assertEquals("PROMISE", projection.memories().getFirst().memoryType(), "关键记忆类型必须保留");
            assertEquals("我现在应在北门，青纹令只剩两枚", fixture.jdbc.queryForObject(
                    "select instruction_text from story_state_correction", String.class));
            assertEquals("FACT", fixture.jdbc.queryForObject(
                    "select correction_type from story_state_correction", String.class));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void auditFailureRollsBackSnapshotAndEntityTogether() {
        EmbeddedDatabase database = database();
        try {
            TestFixture fixture = fixture(database, true);
            String token = fixture.tokens.issue("user-1", "story-save-1", 1, "修正地点与数量", proposal());

            assertThrows(IllegalStateException.class,
                    () -> fixture.service.confirm("user-1", "story-save-1", 1, token));

            var saved = fixture.saves.findOwned("user-1", "story-save-1").orElseThrow();
            var projection = fixture.projections.load(saved, fixture.entities.listAll("story-save-1"),
                    fixture.memories.recentActive("story-save-1", 80));
            assertEquals(1, saved.revision(), "审计失败后快照 revision 必须回滚");
            assertEquals("青岐城南门", projection.coreState().location());
            assertEquals(3, projection.entities().get("item-token").quantity().value(),
                    "审计失败后实体数量必须回滚");
            assertEquals(0, fixture.jdbc.queryForObject(
                    "select count(*) from story_state_correction", Integer.class));
        } finally {
            database.shutdown();
        }
    }

    private TestFixture fixture(EmbeddedDatabase database, boolean failAudit) {
        JdbcTemplate jdbc = new JdbcTemplate(database);
        StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
        StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
        StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
        StoryCorrectionRepository corrections = failAudit
                ? new StoryCorrectionRepository(jdbc, mapper) {
                    @Override
                    public void insert(String userId, String saveId, String instruction, String type,
                                       JsonNode before, JsonNode after) {
                        throw new IllegalStateException("模拟审计写入失败");
                    }
                }
                : new StoryCorrectionRepository(jdbc, mapper);
        StoryProjectionPersistenceMapper projections = new StoryProjectionPersistenceMapper(mapper);
        CorrectionProposalTokenCodec tokens = new CorrectionProposalTokenCodec(mapper,
                "test-only-correction-commit-secret", Duration.ofMinutes(10),
                Clock.fixed(NOW, ZoneOffset.UTC));
        StoryCorrectionAgent unusedAgent = (context, instruction, route) -> {
            throw new AssertionError("确认阶段不应调用修正Agent");
        };
        StoryCorrectionWorkflow workflow = new StoryCorrectionWorkflow(unusedAgent, tokens);
        GameVersionService releases = new GameVersionService(jdbc);
        StoryCorrectionCommitService service = new StoryCorrectionCommitService(saves, entities, memories,
                corrections, projections, workflow, releases,
                new TransactionTemplate(new DataSourceTransactionManager(database)), mapper);
        return new TestFixture(jdbc, saves, entities, memories, projections, tokens, service);
    }

    private EmbeddedDatabase database() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                "user-1", "trusted-correction@test.local", "ACTIVE", now, now);
        jdbc.update("insert into gameplay_config(config_key,config_value,updated_at) values (?,?,?)",
                GameVersionService.CONFIG_KEY, "V0.15", now);

        StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
        saves.insert(new NewSave("story-save-1", "user-1", 1, 1, "ACTIVE", "XUANXU",
                "LIGHT_NARRATIVE", 1, 1, "NATIVE", "V0.14", null, null,
                baseState(), 7, "V0.14", "V0.14", NOW));
        new StoryEntityRepository(jdbc, mapper).insert(new Entity("item-token", "story-save-1", "ITEM",
                "青纹令", "仍在行囊", true, "山门信物", mapper.createObjectNode()
                .put("quantity", 3).put("inPossession", true), 2, 5, NOW, NOW));
        new StoryMemoryRepository(jdbc).insert("story-save-1",
                new StoryMemoryRepository.StoredMemory("memory-promise", "PROMISE", "三年之约",
                        "三年后回到山门", 3, 60, "KEY"));
        return database;
    }

    private ObjectNode baseState() {
        ObjectNode state = mapper.createObjectNode();
        state.put("worldTimeMinutes", 120);
        state.put("currentLocation", "青岐城南门");
        state.put("condition", "平静");
        state.putObject("protagonist").put("name", "陆沉").put("realm", "引气").put("lifeGoal", "问道");
        state.putObject("world").put("code", "XUANXU").put("name", "玄墟界");
        state.put("narrative", "上一回正文仍应保留。");
        return state;
    }

    private StoryCorrectionProposal proposal() {
        return new StoryCorrectionProposal(StoryCorrectionProposal.CorrectionKind.FACT,
                "修正当前地点和青纹令数量。", "确认修正地点与数量？",
                new StoryPatch.CoreStatePatch("青岐城北门", null, null, null),
                List.of(new StoryPatch.EntityPatch(StoryPatch.EntityOperation.UPDATE, "item-token", "ITEM",
                        null, null, null, null, new StoryPatch.QuantityValue(true, 2), true,
                        null, null)), List.of(), List.of());
    }

    private record TestFixture(
            JdbcTemplate jdbc,
            StorySaveRepository saves,
            StoryEntityRepository entities,
            StoryMemoryRepository memories,
            StoryProjectionPersistenceMapper projections,
            CorrectionProposalTokenCodec tokens,
            StoryCorrectionCommitService service
    ) {}
}

package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.credit.PlatformTurnAccessService;
import com.wenxian.game.credit.PlatformTurnQuotaService;
import com.wenxian.game.credit.TurnCreditRepository;
import com.wenxian.game.credit.TurnCreditService;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.ModelCall;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryRecords;
import com.wenxian.game.story.persistence.*;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewSave;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import com.wenxian.game.story.reducer.StoryStateReducer;
import com.wenxian.game.story.support.StoryTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 正式记录提交的本地 H2 事务验证；固定已归并结果，不调用模型、不证明生产数据库恢复。 */
class StoryRecordCommitServiceTest {
    private static final String USER = "record-user", SAVE = "record-save", REQUEST = "record-request";
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commitsRealTurnSourceEvenWhenRecordsAreEmptyAndConsumesOneReservation(boolean withRecords) {
        try (Fixture fixture = fixture(false)) {
            StoredSave before = fixture.saved();
            PreparedStoryTurn prepared = prepared(fixture, withRecords);
            var permit = fixture.access.reserve(USER, SAVE, REQUEST, CredentialSource.PLATFORM);
            fixture.service.commit(USER, before, REQUEST, "请教抄页用途", prepared, permit);

            var turn = new StoryTurnRepository(fixture.jdbc, mapper).findByRequestId(SAVE, REQUEST).orElseThrow();
            var history = StoryRecordHistoryReader.read(turn);
            assertEquals(StoryRecords.PROMPT_VERSION, turn.promptVersion());
            assertFalse(turn.statePatch().has("acceptedResult"));
            assertEquals(turn.id(), history.storyRecords().turnId());
            assertEquals(turn.turnNumber(), history.storyRecords().turnNumber());
            assertEquals(turn.worldTimeBefore(), history.storyRecords().worldTimeBefore());
            assertEquals(turn.worldTimeAfter(), history.storyRecords().worldTimeAfter());
            assertEquals(withRecords ? 2 : 0, history.storyRecords().entries().size());
            if (withRecords) {
                var statement = history.storyRecords().entries().get(1);
                assertEquals(turn.id() + "#record-2", statement.recordId());
                assertEquals("teacher", statement.sourceRef());
                assertEquals("赵先生", statement.sourceLabel());
                assertEquals("缓步时可尝试，快跑尚未验证。", statement.text());
                assertEquals(List.of("paper"), statement.about());
                var readBack = fixture.memories.recentActive(SAVE, 20);
                assertEquals(2, readBack.size());
                assertTrue(readBack.stream().anyMatch(memory -> memory.summary().equals(
                        "赵先生当时表示：缓步时可尝试，快跑尚未验证。")));
            }
            assertEquals(2, fixture.saved().revision());
            assertEquals(1, fixture.saved().turnNumber());
            assertEquals("抄页边缘已做记号", fixture.entities.listAll(SAVE).stream()
                    .filter(entity -> entity.id().equals("paper")).findFirst().orElseThrow().summary());
            assertEquals(19, fixture.credits.balance(USER).available());
            assertEquals(0, fixture.credits.balance(USER).reserved());
            assertEquals(1, fixture.credits.balance(USER).consumed());
            assertEquals(17, turn.promptTokens());
            assertEquals(23, turn.completionTokens());
            assertEquals("本地固定模型", turn.model());
            assertEquals("讲解结束，等待玩家决定如何练习。", fixture.conversations.summary(SAVE));
        }
    }

    @Test
    void failureAfterCreditConsumptionRollsBackRecordsSnapshotEntityMemoryAndSummaryThenReleases() {
        try (Fixture fixture = fixture(true)) {
            StoredSave before = fixture.saved();
            PreparedStoryTurn prepared = prepared(fixture, true);
            var permit = fixture.access.reserve(USER, SAVE, REQUEST, CredentialSource.PLATFORM);

            assertThrows(IllegalStateException.class, () -> fixture.service.commit(USER, before, REQUEST,
                    "请教抄页用途", prepared, permit));
            assertEquals(before.state(), fixture.saved().state());
            assertEquals(before.revision(), fixture.saved().revision());
            assertEquals(0, fixture.saved().turnNumber());
            assertTrue(fixture.turns.recent(SAVE, 20).isEmpty());
            assertTrue(fixture.memories.recentActive(SAVE, 20).isEmpty());
            assertEquals("刚获得的抄页", fixture.entities.listAll(SAVE).stream()
                    .filter(entity -> entity.id().equals("paper")).findFirst().orElseThrow().summary());
            assertEquals("原摘要", fixture.conversations.summary(SAVE));
            assertEquals(0, fixture.credits.balance(USER).consumed());
            assertEquals(1, fixture.credits.balance(USER).reserved(), "外层失败恢复仍负责释放原预占");
            fixture.access.release(permit, "本地事务故障");
            assertEquals(20, fixture.credits.balance(USER).available());
            assertEquals(0, fixture.credits.balance(USER).reserved());
        }
    }

    @Test
    void duplicateRequestAndStaleRevisionCannotAppendDifferentRecordIdsOrConsumeAgain() {
        try (Fixture fixture = fixture(false)) {
            StoredSave before = fixture.saved();
            PreparedStoryTurn prepared = prepared(fixture, true);
            var permit = fixture.access.reserve(USER, SAVE, REQUEST, CredentialSource.PLATFORM);
            fixture.service.commit(USER, before, REQUEST, "请教抄页用途", prepared, permit);
            var original = fixture.turns.findByRequestId(SAVE, REQUEST).orElseThrow();
            var saved = fixture.saved();

            assertThrows(OptimisticLockingFailureException.class, () -> fixture.service.commit(USER, before,
                    REQUEST, "请教抄页用途", prepared, permit));
            assertThrows(DuplicateKeyException.class, () -> fixture.service.commit(USER, saved,
                    REQUEST, "重复提交", prepared(fixture, true), permit));

            assertEquals(original, fixture.turns.findByRequestId(SAVE, REQUEST).orElseThrow(),
                    "引擎幂等读取可返回原章节，不创建第二套记录身份");
            assertEquals(saved.state(), fixture.saved().state());
            assertEquals(saved.revision(), fixture.saved().revision());
            assertEquals(1, fixture.turns.recent(SAVE, 20).size());
            assertEquals(2, fixture.memories.recentActive(SAVE, 20).size());
            assertEquals(1, fixture.credits.balance(USER).consumed());
        }
    }

    private PreparedStoryTurn prepared(Fixture fixture, boolean withRecords) {
        var existing = fixture.entities.listAll(SAVE);
        var before = fixture.projections.load(fixture.saved(), existing, fixture.memories.recentActive(SAVE, 20));
        var records = StoryRecords.prepare(before, List.of(), withRecords ? List.of(
                new StoryRecords.Entry("EVENT", "听过一次调息示范，尚未独立验证。", null, null, List.of("paper")),
                new StoryRecords.Entry("STATEMENT", "缓步时可尝试，快跑尚未验证。", "teacher", null, List.of("paper"))) : List.of());
        var patch = new StoryPatch("抄页上的记号", "赵先生指出抄页上的顺序，讲解适用的情境和仍未验证的限制。",
                new StoryPatch.TimePassage(new SemanticDuration(SemanticDuration.Unit.PHASE, 1, ""),
                        StoryPatch.TimeResolution.COMPLETE, ""),
                new StoryPatch.CoreStatePatch("摊边", null, null, null),
                List.of(new StoryPatch.EntityPatch(StoryPatch.EntityOperation.UPDATE, "paper", "ITEM",
                        null, null, "抄页边缘已做记号", null, null, null, null, null)),
                List.of(), List.of(), records.memories(), null, List.of(), "讲解结束，等待玩家决定如何练习。");
        var applied = new StoryStateReducer().reduce(before, patch);
        var calls = List.of(new ModelCall("LOCAL", "本地固定模型", "PLATFORM", false, "SUCCEEDED", 11,
                17, 23, 3, 14, null));
        return new PreparedStoryTurn(before, applied, existing,
                new PreparedStoryTurn.Generation(applied.acceptedPatch(), calls), List.of(), null, null, null,
                StoryRecords.resolve(records, Map.of(), applied.acceptedPatch().entities()));
    }

    private Fixture fixture(boolean failAfterConsume) {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        var jdbc = new JdbcTemplate(database);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        var credits = new TurnCreditService(new TurnCreditRepository(jdbc), transactions);
        var quota = new PlatformTurnQuotaService(jdbc, credits, true);
        var access = new PlatformTurnAccessService(quota, credits, true) {
            @Override public void consume(Permit permit, String userId, String turnId) {
                super.consume(permit, userId, turnId);
                if (failAfterConsume) throw new IllegalStateException("模拟消费后回合事务故障");
            }
        };
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                USER, "records@test.invalid", "ACTIVE", now, now);
        jdbc.update("insert into gameplay_config(config_key,config_value,updated_at) values (?,?,?)",
                GameVersionService.CONFIG_KEY, "test-release", now);
        quota.ensureDefaults();
        var saves = new StorySaveRepository(jdbc, mapper);
        var turns = new StoryTurnRepository(jdbc, mapper);
        var entities = new StoryEntityRepository(jdbc, mapper);
        var memories = new StoryMemoryRepository(jdbc);
        var conversations = new StoryConversationRepository(jdbc);
        var projections = new StoryProjectionPersistenceMapper(mapper);
        saves.insert(new NewSave(SAVE, USER, 1, 1, "ACTIVE", "XUANXU", "LIGHT_NARRATIVE", 1, 2,
                "NATIVE", "test-release", null, null,
                StoryTestFixtures.state(mapper, SAVE, 0, "摊前", "原正文"), 0, "test-release", "test-release", NOW));
        conversations.initialize(SAVE, "原摘要", 0);
        var teacherAttributes = mapper.createObjectNode();
        teacherAttributes.putObject("_trusted").put("lifecycle", "ACTIVE");
        entities.insert(new Entity("teacher", SAVE, "CHARACTER", "赵先生", "ACTIVE", true,
                "曾指点调息", teacherAttributes, 0, 0, NOW, NOW));
        var attributes = mapper.createObjectNode();
        var trusted = attributes.putObject("_trusted");
        trusted.set("quantity", mapper.valueToTree(new StoryPatch.QuantityValue(true, 1)));
        trusted.set("custody", mapper.valueToTree(new StoryPatch.CustodyValue(StoryPatch.HolderType.PLAYER, null)));
        entities.insert(new Entity("paper", SAVE, "ITEM", "调息抄页", "ACTIVE", true,
                "刚获得的抄页", attributes, 0, 0, NOW, NOW));
        var service = new StoryTurnCommitService(saves, turns, entities, memories, conversations, projections,
                new GameVersionService(jdbc), access, transactions, mapper);
        return new Fixture(database, jdbc, saves, turns, entities, memories, conversations, projections,
                credits, access, service);
    }

    private record Fixture(EmbeddedDatabase database, JdbcTemplate jdbc, StorySaveRepository saves,
                           StoryTurnRepository turns, StoryEntityRepository entities, StoryMemoryRepository memories,
                           StoryConversationRepository conversations, StoryProjectionPersistenceMapper projections,
                           TurnCreditService credits, PlatformTurnAccessService access, StoryTurnCommitService service)
            implements AutoCloseable {
        StoredSave saved() { return saves.findOwned(USER, SAVE).orElseThrow(); }
        @Override public void close() { database.shutdown(); }
    }
}

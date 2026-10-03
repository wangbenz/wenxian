package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.credit.PlatformTurnAccessService;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.ai.DirectorProtocolException;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.ModelCall;
import com.wenxian.game.story.agent.ai.StoryCandidateGenerator;
import com.wenxian.game.story.agent.ai.StoryCandidateProtocol;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryRecords;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.persistence.StoryConversationRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewSave;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import com.wenxian.game.story.persistence.StorySaveRepository;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import com.wenxian.game.story.support.StoryTestFixtures;
import com.wenxian.game.story.worldpackage.WorldPackageRegistryTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 修行时间取舍的临时缺口探针，只刻画当前正式准备与提交链对违规固定候选的接受边界。
 *
 * <p>模型由本地固定 Session 替代，数据仅在新建 H2 中。执行真实 Preparation、Compiler、Reducer、
 * Commit，并用新 Repository/Mapper 实例读回；不读取密钥、不连接供应商或共享数据库。
 * 沿用测试世界包注册表只为满足现有运行时装配，不从旧剧情提取玩法。
 * 本探针不经过 Controller/Engine，不覆盖 HTTP、认证、额度预占，不证明真实模型概率、生产部署或完整默认链。
 * 世界事实中的三日门槛、截止与一次性声明是合成实验条件，不冒充已经接入的 Loreforge 可执行规则。</p>
 *
 * <p>其中反向断言成立表示发现缺口，绝不是新玩法通过。Probe 后缀使默认 Surefire 不自动运行；
 * 仅显式 -Dtest=CultivationTimeRuntimeProbe 执行。未来正式规则改变时应重新审视或删除探针，不能为了保绿恢复违规行为。</p>
 */
@Tag("gameplay-gap-probe")
class CultivationTimeRuntimeProbe {
    private static final String USER = "probe-user";
    private static final String SAVE = "probe-save";
    private static final String ABILITY = "probe-yuwu";
    private static final String TALISMAN = "probe-talisman";
    private static final String LEARNING_FACT = "probe-learning-rule";
    private static final String COMMISSION_FACT = "probe-commission-rule";
    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @EnumSource(value = StoryPatch.AbilityState.class, names = {"KNOWN", "LEARNING"})
    void observesMissingThreeDayGateWhenOneSceneCandidateMastersAbility(StoryPatch.AbilityState initial) {
        try (Fixture fixture = fixture(1, LEARNING_FACT, "御物术须累计学习三日方可掌握。")) {
            fixture.ability(initial);
            StoryProjection before = fixture.projection();
            assertEquals("御物术须累计学习三日方可掌握。", before.worldFacts().get(LEARNING_FACT).statement());
            assertEquals("御物术须累计学习三日方可掌握。", before.worldFacts().get(LEARNING_FACT).statement());
            ObjectNode raw = candidate("已掌握御物术。 ");
            ((ObjectNode) raw.get("changes")).putArray("abilityChanges").addObject()
                    .put("existingEntityId", ABILITY).put("entityType", "ABILITY")
                    .put("summary", "已掌握").put("abilityState", "MASTERED");
            FixedSession session = new FixedSession(raw);

            PreparedStoryTurn prepared = execute(fixture, "probe-master-" + initial, "尝试学习御物术。", session);

            StoryProjection reloaded = fixture.projection();
            assertEquals(1, session.contexts.size(), "若需要修复，应单独记录实际边界变化");
            assertTrue(session.contexts.getFirst().objective().worldFacts().stream()
                    .anyMatch(fact -> fact.id().equals(LEARNING_FACT)
                            && fact.statement().equals(before.worldFacts().get(LEARNING_FACT).statement())),
                    "三日文字条件已从权威投影进入实际模型上下文，不冒充可执行规则");
            assertEquals(initial, before.entities().get(ABILITY).abilityState());
            assertEquals(StoryPatch.AbilityState.MASTERED, reloaded.entities().get(ABILITY).abilityState(),
                    "缺口观测：当前准备和事务链接受一场景掌握，不代表三日玩法通过");
            assertEquals(before.worldTime(), reloaded.worldTime(), "SCENE 不推进权威时钟");
            assertEquals("SCENE", prepared.applied().acceptedPatch().time().duration().unit().name());
            assertEquals(1, fixture.turns().size());
            assertEquals("已掌握御物术。", fixture.saved().state().path("scene").path("narrative").asText().strip());
            assertEquals(2, fixture.saved().revision());
        }
    }

    @Test
    void observesSettledExpiredCommissionRewardAcceptedTwiceUnderDifferentRequestIds() {
        String fact = "委托甲截至第4日；委托已完成且报酬已结清，只能领取一次。";
        try (Fixture fixture = fixture(5, COMMISSION_FACT, fact)) {
            fixture.item(TALISMAN, "搬运符", 1, "张");
            StoryProjection before = fixture.projection();
            assertEquals(fact, before.worldFacts().get(COMMISSION_FACT).statement());
            assertEquals(5, before.worldTime().day());
            ObjectNode raw = candidate("委托甲再次发放两张搬运符。 ");
            ObjectNode step = ((ObjectNode) raw.get("changes")).putObject("assetProcess")
                    .putArray("steps").addObject().put("kind", "ACQUIRE").put("summary", "委托甲报酬");
            step.putArray("inputs");
            step.putArray("observations");
            step.putArray("outputs").addObject().put("entityRef", TALISMAN).put("amount", "2").put("unit", "张")
                    .putObject("custody").put("holderType", "PLAYER").putNull("holderRef").putNull("localHolderRef");
            FixedSession session = new FixedSession(raw);

            execute(fixture, "probe-reward-1", "领取委托甲报酬。", session);
            assertEquals(3, fixture.projection().entities().get(TALISMAN).quantity().value());
            execute(fixture, "probe-reward-2", "再次领取委托甲报酬。", session);

            StoryProjection reloaded = fixture.projection();
            assertEquals(2, session.contexts.size());
            assertTrue(session.contexts.stream().allMatch(context -> context.objective().worldFacts().stream()
                    .anyMatch(value -> value.id().equals(COMMISSION_FACT) && value.statement().equals(fact))));
            assertNotNull(session.contexts.getLast().narrative().previousTurn(), "第二轮实际读到了上一轮奖励正文");
            assertTrue(session.contexts.getLast().narrative().previousTurn().narrative().contains("两张搬运符"));
            assertEquals(5, reloaded.worldTime().day());
            assertEquals(5, reloaded.entities().get(TALISMAN).quantity().value(),
                    "缺口观测：新 requestId 下再次入账，不能将请求幂等当作委托奖励恰一次");
            assertEquals(List.of("probe-reward-1", "probe-reward-2"),
                    fixture.turns().stream().map(turn -> turn.requestId()).toList());
            assertEquals(3, fixture.saved().revision());
            assertEquals(2, fixture.saved().turnNumber());
            assertTrue(fixture.turns().stream().allMatch(turn ->
                    turn.statePatch().path("assetProcess").path("steps").size() == 1));
        }
    }

    @Test
    void explicitConsumptionCommandDoesPersistAsZeroStock() {
        try (Fixture fixture = fixture(1, "probe-talisman-rule", "搬运符为一次性物品，使用一张即消耗一张。")) {
            fixture.item(TALISMAN, "搬运符", 1, "张");
            ObjectNode raw = candidate("使用一张搬运符完成搬运，符已耗尽。");
            ObjectNode step = ((ObjectNode) raw.get("changes")).putObject("assetProcess")
                    .putArray("steps").addObject().put("kind", "CONSUME").put("summary", "使用搬运符");
            step.putArray("outputs");
            step.putArray("observations");
            step.putArray("inputs").addObject().put("entityRef", TALISMAN).put("amount", "1").put("unit", "张")
                    .putObject("custody").put("holderType", "PLAYER").putNull("holderRef").putNull("localHolderRef");
            FixedSession session = new FixedSession(raw);

            execute(fixture, "probe-talisman-explicit", "使用一张搬运符。", session);

            var item = fixture.projection().entities().get(TALISMAN);
            assertEquals(1, session.contexts.size());
            assertEquals(0, item.quantity().value(), "正向对照：明确的消耗指令确实落库，缺口不在库存持久化");
            assertEquals(StoryPatch.HolderType.NONE, item.custody().holderType());
            assertEquals(1, fixture.turns().size());
        }
    }

    @Test
    void observesNarratedSingleUseTalismanConsumptionWithoutInventoryDeduction() {
        try (Fixture fixture = fixture(1, "probe-talisman-rule", "搬运符为一次性物品，使用一张即消耗一张。")) {
            fixture.item(TALISMAN, "搬运符", 1, "张");
            StoryProjection before = fixture.projection();
            assertEquals("搬运符为一次性物品，使用一张即消耗一张。",
                    before.worldFacts().get("probe-talisman-rule").statement());
            ObjectNode raw = candidate("使用一张搬运符完成搬运，符已耗尽。 ");
            ((ObjectNode) raw.get("changes")).putObject("assetProcess").putArray("steps");
            FixedSession session = new FixedSession(raw);

            PreparedStoryTurn prepared = execute(fixture, "probe-talisman-1", "使用一张搬运符。", session);

            StoryProjection reloaded = fixture.projection();
            assertEquals(1, session.contexts.size());
            assertTrue(session.contexts.getFirst().objective().worldFacts().stream()
                    .anyMatch(fact -> fact.id().equals("probe-talisman-rule")
                            && fact.statement().equals(before.worldFacts().get("probe-talisman-rule").statement())));
            assertTrue(prepared.assetProcess().steps().isEmpty());
            assertEquals(1, reloaded.entities().get(TALISMAN).quantity().value(),
                    "缺口观测：正文宣布消耗但候选遗漏过程，当前链仍提交并保留库存");
            assertEquals(StoryPatch.HolderType.PLAYER, reloaded.entities().get(TALISMAN).custody().holderType());
            assertTrue(fixture.turns().getFirst().narrative().contains("符已耗尽"));
            assertTrue(fixture.saved().state().path("scene").path("narrative").asText().contains("符已耗尽"));
            assertEquals(1, fixture.turns().size());
        }
    }

    /** 每次调用均新装配正式准备与提交服务，提交后由 Fixture 的新仓储重新读取。 */
    private PreparedStoryTurn execute(Fixture fixture, String requestId, String action, FixedSession session) {
        JdbcTemplate jdbc = new JdbcTemplate(fixture.database);
        TrustedStoryTurnPreparationService preparation = new TrustedStoryTurnPreparationService(
                new StoryEntityRepository(jdbc, mapper), new StoryMemoryRepository(jdbc),
                new StoryConversationRepository(jdbc), new StoryTurnRepository(jdbc, mapper),
                new StoryProjectionPersistenceMapper(mapper), new PromptContextAssembler(),
                WorldPackageRegistryTestSupport.worldContexts(mapper), null, invocation -> session);
        StoredSave before = fixture.saved();
        PreparedStoryTurn prepared = preparation.prepare(before, requestId, action, null,
                preparation.openSession(before, requestId));
        StoryTurnCommitService commit = new StoryTurnCommitService(new StorySaveRepository(jdbc, mapper),
                new StoryTurnRepository(jdbc, mapper), new StoryEntityRepository(jdbc, mapper),
                new StoryMemoryRepository(jdbc), new StoryConversationRepository(jdbc),
                new StoryProjectionPersistenceMapper(mapper), new GameVersionService(jdbc),
                new PlatformTurnAccessService(null, null, false),
                new TransactionTemplate(new DataSourceTransactionManager(fixture.database)), mapper);
        commit.commit(USER, before, requestId, action, prepared, null);
        assertEquals(before.turnNumber() + 1, fixture.saved().turnNumber());
        return prepared;
    }

    private ObjectNode candidate(String narrative) {
        ObjectNode raw = mapper.createObjectNode();
        ObjectNode story = raw.putObject("story").put("title", "探针").put("narrative", narrative.strip())
                .putNull("decision").put("summary", narrative.strip());
        story.putArray("choices");
        ObjectNode changes = raw.putObject("changes").put("timeResolution", "COMPLETE");
        changes.putObject("duration").put("unit", "SCENE").put("value", 1).put("targetPhase", "");
        raw.putArray("records");
        return raw;
    }

    /** 固定模型边界，保留真实准备阶段构造的上下文；任何协议修复都会在测试中被计数。 */
    private final class FixedSession implements StoryCandidateGenerator.Session {
        private final ObjectNode raw;
        private final List<StoryPromptContext> contexts = new ArrayList<>();

        private FixedSession(ObjectNode raw) { this.raw = raw; }
        @Override public CredentialSource credentialSource() { return CredentialSource.PLATFORM; }
        @Override public StoryCandidateGenerator.Generated generate(StoryPromptContext context,
                List<StoryRecords.Envelope> history, DirectorProtocolException rejected, Object alignment) {
            contexts.add(context);
            try {
                var inspection = StoryCandidateProtocol.inspect(raw.toString());
                assertTrue(inspection.problems().isEmpty(), () -> "固定候选必须先满足正式格式检查：" + inspection.problems());
                StoryCandidateProtocol.Draft draft = mapper.readValue(inspection.normalizedJson(), StoryCandidateProtocol.Draft.class);
                return new StoryCandidateGenerator.Generated(draft, StoryCandidateProtocol.adapt(draft),
                        List.of(new ModelCall("OFFLINE", "fixed-probe", "PLATFORM", false, "SUCCESS", 0,
                                0, 0, 0, 0, null)), raw.toString(), List.of());
            } catch (Exception error) {
                throw new AssertionError("本地固定候选构造失败", error);
            }
        }
    }

    private Fixture fixture(int day, String factId, String factText) {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                USER, "probe@test.invalid", "ACTIVE", Timestamp.from(NOW), Timestamp.from(NOW));
        ObjectNode state = StoryTestFixtures.state(mapper, SAVE, 0, "测试地点", "测试开始。");
        state.set("worldTime", mapper.valueToTree(new StoryWorldTime(1, day, StoryWorldTime.Phase.MORNING)));
        ((ObjectNode) state.path("trustedProjection")).putArray("worldFacts")
                .addObject().put("id", factId).put("statement", factText).put("revision", 1)
                .put("active", true).put("lastOperation", "EXPAND").put("lastReason", "实验前提");
        new StorySaveRepository(jdbc, mapper).insert(new NewSave(SAVE, USER, 1, 1, "ACTIVE", "XUANXU",
                "LIGHT_NARRATIVE", 1, 2, "NATIVE", "LOCAL_PROBE", null, null, state,
                0, "LOCAL_PROBE", "LOCAL_PROBE", NOW));
        new StoryConversationRepository(jdbc).initialize(SAVE, "测试开始。", 0);
        return new Fixture(database);
    }

    /** 独立 H2 生命周期；每次读回都新建仓储和映射器，避免以内存 Prepared 值冒充持久化结果。 */
    private final class Fixture implements AutoCloseable {
        private final EmbeddedDatabase database;
        private Fixture(EmbeddedDatabase database) { this.database = database; }

        private StoredSave saved() {
            return new StorySaveRepository(new JdbcTemplate(database), mapper).findOwned(USER, SAVE).orElseThrow();
        }
        private StoryProjection projection() {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            return new StoryProjectionPersistenceMapper(mapper).load(saved(),
                    new StoryEntityRepository(jdbc, mapper).listAll(SAVE),
                    new StoryMemoryRepository(jdbc).recentActive(SAVE, 20));
        }
        private List<com.wenxian.game.story.persistence.StoryPersistenceModels.StoredTurn> turns() {
            return new StoryTurnRepository(new JdbcTemplate(database), mapper).recent(SAVE, 20);
        }
        private void ability(StoryPatch.AbilityState initial) {
            ObjectNode attributes = mapper.createObjectNode();
            attributes.putObject("_trusted").put("abilityState", initial.name());
            new StoryEntityRepository(new JdbcTemplate(database), mapper).insert(new Entity(ABILITY, SAVE,
                    "ABILITY", "御物术", "ACTIVE", true, "尚未掌握", attributes, 0, 0, NOW, NOW));
        }
        private void item(String id, String name, int quantity, String unit) {
            ObjectNode attributes = mapper.createObjectNode().put("quantityUnit", unit);
            ObjectNode trusted = attributes.putObject("_trusted");
            trusted.putObject("quantity").put("known", true).put("value", quantity);
            trusted.putObject("custody").put("holderType", "PLAYER").putNull("holderRef");
            new StoryEntityRepository(new JdbcTemplate(database), mapper).insert(new Entity(id, SAVE,
                    "ITEM", name, "ACTIVE", true, "测试物品", attributes, 0, 0, NOW, NOW));
        }
        @Override public void close() { database.shutdown(); }
    }
}

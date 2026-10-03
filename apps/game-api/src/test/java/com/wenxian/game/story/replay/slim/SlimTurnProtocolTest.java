package com.wenxian.game.story.replay.slim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.agent.ai.DirectorAssetDraftAdapter;
import com.wenxian.game.story.agent.ai.TurnDirectorAi;
import com.wenxian.game.story.domain.AssetProcess;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryFactIntentCompiler;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryPatchValidator;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.reducer.StoryStateReducer;
import com.wenxian.game.story.support.StoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.wenxian.game.story.replay.slim.SlimTurnProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离协议的纯转换与生产领域回归；不调用模型、不连接数据库、不改变晋升规则。 */
class SlimTurnProtocolTest {
    private static final TurnDirectorAi.CustodyDraft PLAYER = new TurnDirectorAi.CustodyDraft("PLAYER", "", "");
    private static final OrderedTurnResult.Stop DONE = new OrderedTurnResult.Stop(OrderedTurnResult.StopReason.ACTION_RESOLVED, "");
    private static final String TEACHING = "先静坐缓缓呼气；走快时若气息散乱，就停下重新调匀。";
    private final ObjectMapper mapper = new ObjectMapper();
    private final StoryFactIntentCompiler compiler = new StoryFactIntentCompiler();

    @Test
    void nestedAssetsMatchFixedCurrentContractWithoutInventingActionUnderstanding() {
        var acquire = asset(AssetProcess.Kind.ACQUIRE, List.of(), List.of(flow("copy", "1", "册")));
        var exchange = asset(AssetProcess.Kind.EXCHANGE, List.of(flow("herb", "2", "份")), List.of(flow("coin", "50", "文")));
        var statement = new OrderedTurnResult.Statement("teacher", "", TEACHING);
        var teaching = new Step("请教调息并接过抄本", OrderedTurnResult.Resolution.SUCCEEDED,
                "静坐时能够照着节奏缓呼气，尚未试过行走；收到一册抄本。", false, List.of(), List.of(statement), List.of(acquire));
        var sale = step("卖出两份药草", List.of(exchange));
        var item = new TurnDirectorAi.ItemDraft("", "copy", "吐纳抄本", "已取得", "写有缓呼气的练法", List.of(), true);
        var ability = new AbilityDraft("", "breathing", "缓息法", "学习中", "静坐时能照做，行走效果尚未验证", List.of(), "LEARNING", true);
        Draft slim = new Draft(duration(), TurnDirectorAi.TimeResolution.COMPLETE, "", null, null,
                List.of(item), List.of(ability), List.of(), List.of(), new ResultDraft(List.of(teaching, sale), DONE));
        StoryTurnDecision converted = toDecision(slim);

        // 固定基线显式使用当前内部索引和实体合同，不通过精简适配器构造期望结果。
        StoryTurnDecision baseline = new StoryTurnDecision(null, new SemanticDuration(SemanticDuration.Unit.SCENE, 1, ""),
                StoryPatch.TimeResolution.COMPLETE, "", null, null, List.of(
                new StoryTurnDecision.EntityIntent("", "copy", "ITEM", item.name(), item.status(), item.summary(),
                        null, null, null, null, null, null, true),
                new StoryTurnDecision.EntityIntent("", "breathing", "ABILITY", ability.name(), ability.status(), ability.summary(),
                        null, null, null, StoryPatch.AbilityState.LEARNING, null, null, true)),
                List.of(), List.of(), null, List.of(), new DirectorAssetDraftAdapter().toDomain(
                new TurnDirectorAi.AssetProcessDraft(List.of(acquire, exchange))),
                new StoryTurnDecision.ActionUnderstanding("请教并出售草药", List.of(), List.of()),
                new OrderedTurnResult.Proposal(List.of(
                        new OrderedTurnResult.Step(teaching.action(), teaching.resolution(), teaching.result(), false,
                                List.of(0), List.of(), List.of(statement)),
                        new OrderedTurnResult.Step(sale.action(), sale.resolution(), sale.result(), false,
                                List.of(1), List.of(), List.of())), DONE));

        StoryProjection before = before(new StoryPatch.QuantityValue(true, 2));
        var expected = compiler.compileDetailed(before, baseline);
        var actual = compiler.compileDetailed(before, converted);
        assertNull(converted.actionUnderstanding());
        assertEquals(List.of(0), converted.orderedResult().steps().getFirst().assetStepIndexes());
        assertEquals(List.of(1), converted.orderedResult().steps().getLast().assetStepIndexes());
        assertEquals(expected.process(), actual.process());
        assertEquals(expected.acceptedResult(), actual.acceptedResult());
        assertEquals(expected.patch(), actual.patch());
        assertEquals(TEACHING, actual.acceptedResult().steps().getFirst().statements().getFirst().statement());
        assertEquals("teacher", actual.acceptedResult().steps().getFirst().statements().getFirst().sourceRef());
        assertTrue(actual.patch().memories().stream().anyMatch(memory -> memory.summary().contains(TEACHING)));
        var expectedApplied = reducer().reduce(before, expected.patch());
        var actualApplied = reducer().reduce(before, actual.patch());
        assertEquals(expectedApplied.projection(), actualApplied.projection());
        assertEquals(70, actualApplied.projection().entities().get("coin").quantity().value());
        assertEquals(0, actualApplied.projection().entities().get("herb").quantity().value());
        assertTrue(actualApplied.projection().entities().values().stream().anyMatch(entity ->
                "缓息法".equals(entity.name()) && entity.abilityState() == StoryPatch.AbilityState.LEARNING));
        var resolved = AssetProcess.resolveRefs(actual.process(), actualApplied.acceptedPatch().entities());
        assertNotEquals("copy", resolved.steps().getFirst().outputs().getFirst().entityRef());
        assertEquals(2, before.entities().get("herb").quantity().value());
    }

    @Test
    void repairRoundTripUsesTheSameNarrowShapeAndKeepsTeachingAndAssetOwnership() throws Exception {
        var teaching = new Step("请教", OrderedTurnResult.Resolution.SUCCEEDED, "听懂了缓呼气的方法，尚未练熟", false,
                List.of(), List.of(new OrderedTurnResult.Statement("teacher", "", TEACHING)), List.of());
        var sale = new Step("卖药", OrderedTurnResult.Resolution.SUCCEEDED, "交出两份草药并收到五十文", false,
                List.of(new OrderedTurnResult.Dependency(0, OrderedTurnResult.Expected.SUCCEEDED)), List.of(),
                List.of(asset(AssetProcess.Kind.EXCHANGE, List.of(flow("herb", "ALL", "份")), List.of(flow("coin", "50", "文")))));
        var decision = toDecision(draft(List.of(teaching, sale)));
        Draft restored = fromDecision(decision);
        assertEquals(decision, toDecision(restored));
        var json = mapper.valueToTree(restored);
        assertFalse(json.has("assetProcess"));
        assertFalse(json.has("actionUnderstanding"));
        var second = json.path("orderedResult").path("steps").get(1);
        assertFalse(second.has("assetStepIndexes"));
        assertEquals("ALL", second.path("assets").get(0).path("inputs").get(0).path("amount").asText());
        assertFalse(second.path("assets").get(0).has("stepId"));
        assertFalse(second.path("assets").get(0).path("inputs").get(0).has("all"));
        assertEquals(restored, mapper.readValue(mapper.writeValueAsString(restored), Draft.class));
    }

    @Test
    void teachingWithRealmProgressKeepsExperienceAndStatementAlongsideServerResult() {
        var step = new Step("请教后练习", OrderedTurnResult.Resolution.SUCCEEDED, "照指点缓呼气，静坐时气息逐渐稳定", true,
                List.of(), List.of(new OrderedTurnResult.Statement("teacher", "", TEACHING)), List.of());
        var plain = draft(List.of(step));
        var withGrowth = new Draft(plain.duration(), plain.timeResolution(), "", null,
                new TurnDirectorAi.CultivationDraft(true, false, "调息练习形成进展", List.of("PLAYER_ACTION")),
                plain.itemChanges(), plain.abilityChanges(), plain.entities(), plain.worldFacts(), plain.orderedResult());
        var before = before(new StoryPatch.QuantityValue(true, 2));
        var cultivationBefore = new StoryProjection(before.worldTime(), before.coreState(), before.entities(),
                before.worldFacts(), before.knowledge(), before.memories(), before.creationContract(), before.playPreference(),
                StoryTestFixtures.cultivationSystem(), StoryTestFixtures.protagonistCultivation());
        var accepted = compiler.compileDetailed(cultivationBefore, toDecision(withGrowth)).acceptedResult().steps().getFirst();
        assertEquals(step.result(), accepted.result());
        assertEquals(TEACHING, accepted.statements().getFirst().statement());
        assertNotNull(accepted.realmResult());
        assertEquals(StoryPatch.CultivationResolution.PROGRESSED, accepted.realmResult().resolution());
    }

    @Test
    void omittedOrMismatchedRealmBindingStillFailsUnderCurrentRules() {
        var boundWithoutIntent = new Step("静坐练习", OrderedTurnResult.Resolution.SUCCEEDED, "练习了缓呼气", true,
                List.of(), List.of(), List.of());
        assertEquals("CULTIVATION_BINDING", assertThrows(OrderedTurnResult.ProtocolException.class,
                () -> compiler.compileDetailed(before(new StoryPatch.QuantityValue(true, 2)),
                        toDecision(draft(List.of(boundWithoutIntent))))).code());
        var missing = new Step("静坐练习", OrderedTurnResult.Resolution.SUCCEEDED, "练习了缓呼气", null,
                List.of(), List.of(), List.of());
        assertEquals("BINDING_REQUIRED", assertThrows(OrderedTurnResult.ProtocolException.class,
                () -> compiler.compileDetailed(before(new StoryPatch.QuantityValue(true, 2)),
                        toDecision(draft(List.of(missing))))).code());
    }

    @Test
    void unknownOldQuantityIsNotZeroAndCannotBypassKnownBalance() {
        var consume = draft(List.of(step("取用", List.of(asset(AssetProcess.Kind.CONSUME,
                List.of(flow("herb", "1", "份")), List.of())))));
        var unknown = new StoryPatch.QuantityValue(false, null);
        assertEquals(unknown, compiler.compileDetailed(before(unknown), toDecision(consume))
                .process().steps().getFirst().inputs().getFirst().afterQuantity());
        var unknownAmount = draft(List.of(step("取用", List.of(asset(AssetProcess.Kind.CONSUME,
                List.of(flow("herb", "UNKNOWN", "份")), List.of())))));
        assertEquals("UNKNOWN_DEDUCTION", assertThrows(AssetProcess.ProtocolException.class,
                () -> compiler.compileDetailed(before(new StoryPatch.QuantityValue(true, 2)), toDecision(unknownAmount))).code());
        var excessive = draft(List.of(step("取用", List.of(asset(AssetProcess.Kind.CONSUME,
                List.of(flow("herb", "3", "份")), List.of())))));
        assertEquals("INSUFFICIENT_QUANTITY", assertThrows(AssetProcess.ProtocolException.class,
                () -> compiler.compileDetailed(before(new StoryPatch.QuantityValue(true, 2)), toDecision(excessive))).code());
    }

    @Test
    void inventoryRemainsOrderedBeforeConsumptionAndCannotOverwriteKnownBalance() {
        var count = new TurnDirectorAi.AssetStepDraft(AssetProcess.Kind.INVENTORY, "实际清点", List.of(), List.of(),
                List.of(new TurnDirectorAi.ObservationDraft("herb", new TurnDirectorAi.QuantityDraft(true, 3), "份", PLAYER, "逐份数得三份")));
        var use = asset(AssetProcess.Kind.CONSUME, List.of(flow("herb", "2", "份")), List.of());
        var decision = toDecision(draft(List.of(step("先清点再取用", List.of(count, use)))));
        var compiled = compiler.compileDetailed(before(new StoryPatch.QuantityValue(false, null)), decision);
        assertEquals(1, compiled.process().steps().getLast().inputs().getFirst().afterQuantity().value());
        assertEquals("INVENTORY_OVERWRITE", assertThrows(AssetProcess.ProtocolException.class,
                () -> compiler.compileDetailed(before(new StoryPatch.QuantityValue(true, 2)), decision)).code());
    }

    @Test
    void nestingDoesNotRelaxStableIdentitySourceCustodyOrUnitChecks() {
        var wrongRef = draft(List.of(step("取用", List.of(asset(AssetProcess.Kind.CONSUME,
                List.of(flow("药草", "1", "份")), List.of())))));
        assertEquals("UNKNOWN_ITEM", assertThrows(AssetProcess.ProtocolException.class,
                () -> compiler.compileDetailed(before(new StoryPatch.QuantityValue(true, 2)), toDecision(wrongRef))).code());
        var wrongSource = new TurnDirectorAi.FlowDraft("herb", "1", "份",
                new TurnDirectorAi.CustodyDraft("CHARACTER", "teacher", ""));
        assertEquals("SOURCE_CUSTODY", assertThrows(AssetProcess.ProtocolException.class,
                () -> compiler.compileDetailed(before(new StoryPatch.QuantityValue(true, 2)), toDecision(draft(List.of(step("取用",
                        List.of(asset(AssetProcess.Kind.CONSUME, List.of(wrongSource), List.of())))))))).code());
        var changedUnit = draft(List.of(step("先取一份再取一斤", List.of(
                asset(AssetProcess.Kind.CONSUME, List.of(flow("herb", "1", "份")), List.of()),
                asset(AssetProcess.Kind.CONSUME, List.of(flow("herb", "1", "斤")), List.of())))));
        assertEquals("UNIT_MISMATCH", assertThrows(AssetProcess.ProtocolException.class,
                () -> compiler.compileDetailed(before(new StoryPatch.QuantityValue(true, 2)), toDecision(changedUnit))).code());
    }

    @Test
    void literalRulesAndRawStringTypeAreUnchanged() {
        for (String invalid : List.of("0", "-1", "一", "ALL+1", "2147483648", " 1", "1.5")) {
            assertThrows(AssetProcess.ProtocolException.class, () -> toDecision(draft(List.of(step("取用",
                    List.of(asset(AssetProcess.Kind.CONSUME, List.of(flow("herb", invalid, "份")), List.of())))))), invalid);
        }
        assertThrows(AssetProcess.ProtocolException.class, () -> toDecision(draft(List.of(step("取得",
                List.of(asset(AssetProcess.Kind.ACQUIRE, List.of(), List.of(flow("coin", "ALL", "文")))))))));
        assertThrows(JsonProcessingException.class, () -> mapper.readValue("""
                {"kind":"CONSUME","summary":"取用","inputs":[{"entityRef":"herb","amount":2}],"outputs":[],"observations":[]}
                """, TurnDirectorAi.AssetStepDraft.class));
    }

    @Test
    void itemProtectedAttributesAndWrongEntityGroupingAreRejected() {
        for (String key : List.of("quantity", "quantityUnit", "custody", "balance", "_trusted")) {
            Draft plain = draft(List.of(step("查看", List.of())));
            Draft invalid = new Draft(plain.duration(), plain.timeResolution(), "", null, null,
                    List.of(new TurnDirectorAi.ItemDraft("herb", "", "", "", "", List.of(new TurnDirectorAi.AttributeDraft(key, "1")), true)),
                    List.of(), List.of(), List.of(), plain.orderedResult());
            assertTrue(assertThrows(IllegalArgumentException.class, () -> toDecision(invalid)).getMessage().contains(key));
        }
        var plain = draft(List.of(step("查看", List.of())));
        var wrong = new TurnDirectorAi.EntityDraft("", "skill", "ABILITY", "缓息法", "", "", null,
                List.of(), null, null, "LEARNING", null, null, true);
        assertThrows(IllegalArgumentException.class, () -> toDecision(new Draft(plain.duration(), plain.timeResolution(), "",
                null, null, List.of(), List.of(), List.of(wrong), List.of(), plain.orderedResult())));
    }

    @Test
    void emptyEntriesMissingStepsAndMissingAssetListsAreNotSilentlyDropped() {
        assertThrows(IllegalArgumentException.class, () -> toDecision(draft(List.of())));
        assertThrows(IllegalArgumentException.class, () -> toDecision(draft(Arrays.asList((Step) null))));
        assertThrows(IllegalArgumentException.class, () -> toDecision(draft(List.of(new Step("查看", OrderedTurnResult.Resolution.SUCCEEDED,
                "查看完成", false, List.of(), List.of(), null)))));
        assertThrows(IllegalArgumentException.class, () -> toDecision(draft(List.of(step("查看", Arrays.asList((TurnDirectorAi.AssetStepDraft) null))))));
        Draft plain = draft(List.of(step("查看", List.of())));
        assertThrows(IllegalArgumentException.class, () -> toDecision(new Draft(plain.duration(), plain.timeResolution(), "", null, null,
                Arrays.asList((TurnDirectorAi.ItemDraft) null), List.of(), List.of(), List.of(), plain.orderedResult())));
    }

    @Test
    void cannotSettleAssetsAttachedToUnexecutedActionOrSkipRequiredExchangeSide() {
        var acquire = asset(AssetProcess.Kind.ACQUIRE, List.of(), List.of(flow("coin", "1", "文")));
        var notExecuted = new Step("不买", OrderedTurnResult.Resolution.NOT_EXECUTED, "超过价格上限，未购买", false,
                List.of(), List.of(), List.of(acquire));
        assertEquals("UNEXECUTED_EFFECT", assertThrows(OrderedTurnResult.ProtocolException.class,
                () -> compiler.compileDetailed(before(new StoryPatch.QuantityValue(true, 2)), toDecision(draft(List.of(notExecuted))))).code());
        var incomplete = asset(AssetProcess.Kind.EXCHANGE, List.of(flow("herb", "1", "份")), List.of());
        assertEquals("INCOMPLETE_PROCESS", assertThrows(AssetProcess.ProtocolException.class,
                () -> compiler.compileDetailed(before(new StoryPatch.QuantityValue(true, 2)), toDecision(draft(List.of(step("卖出", List.of(incomplete))))))).code());
    }

    @Test
    void exactStepAndAssetLimitsRemainGlobalAfterNesting() {
        assertEquals(16, toDecision(draft(IntStream.range(0, 16).mapToObj(i -> step("查看" + i, List.of())).toList())).orderedResult().steps().size());
        assertThrows(IllegalArgumentException.class, () -> toDecision(draft(IntStream.range(0, 17).mapToObj(i -> step("查看" + i, List.of())).toList())));
        var one = asset(AssetProcess.Kind.CONSUME, List.of(flow("herb", "1", "份")), List.of());
        var thirtyTwo = IntStream.range(0, 32).mapToObj(i -> one).toList();
        assertEquals(64, toDecision(draft(List.of(step("前半", thirtyTwo), step("后半", thirtyTwo)))).assetProcess().steps().size());
        var thirtyThree = new ArrayList<>(thirtyTwo);
        thirtyThree.add(one);
        assertThrows(IllegalArgumentException.class, () -> toDecision(draft(List.of(step("前半", thirtyTwo), step("后半", thirtyThree)))));
    }

    @Test
    void repairNeverDropsUnassignedAssetHistory() {
        var normal = toDecision(draft(List.of(step("取得", List.of(asset(AssetProcess.Kind.ACQUIRE, List.of(), List.of(flow("coin", "1", "文"))))))));
        var detached = new StoryTurnDecision(null, normal.duration(), normal.timeResolution(), "", null, null,
                List.of(), List.of(), List.of(), null, List.of(), normal.assetProcess(), null,
                new OrderedTurnResult.Proposal(List.of(new OrderedTurnResult.Step("查看", OrderedTurnResult.Resolution.SUCCEEDED,
                        "查看完成", false, List.of(), List.of(), List.of())), DONE));
        assertThrows(IllegalArgumentException.class, () -> fromDecision(detached));
    }

    @Test
    void repairFeedbackTranslatesOnlyKnownAssetPathsWithoutParsingLanguage() {
        var asset = asset(AssetProcess.Kind.CONSUME, List.of(flow("herb", "1", "份")), List.of());
        var draft = draft(List.of(step("先查看", List.of()), step("再取用", List.of(asset, asset))));
        String original = "assetProcess.steps[1].inputs[0].amount: 数量不足；assetProcess.steps[0].unit: 单位冲突；assetProcess.steps[99] 未知";
        String expected = "orderedResult.steps[1].assets[1].inputs[0].amount: 数量不足；orderedResult.steps[1].assets[0].unit: 单位冲突；assetProcess.steps[99] 未知";
        assertEquals(expected, repairProblem(draft, original));
        assertEquals(expected, repairProblem(toDecision(draft), original));
        var invalid = draft(List.of(step("取用", List.of(asset(AssetProcess.Kind.CONSUME,
                List.of(flow("herb", "ALL+1", "份")), List.of())))));
        assertEquals("orderedResult.steps[0].assets[0].inputs[0].amount 无效",
                repairProblem(invalid, "assetProcess.steps[0].inputs[0].amount 无效"));
    }

    @Test
    void abilityWireHasOnlyRelevantFieldsAndRejectsRemovedFields() throws Exception {
        var ability = new AbilityDraft("", "breath", "缓息法", "学习中", "静坐练习", List.of(), "LEARNING", true);
        var json = mapper.valueToTree(ability);
        assertEquals(8, json.size());
        for (String removed : List.of("entityType", "quantity", "custody", "lifecycle", "relationship", "scenePresence")) {
            assertFalse(json.has(removed));
            var invalid = json.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).putNull(removed);
            assertThrows(JsonProcessingException.class, () -> mapper.treeToValue(invalid, AbilityDraft.class));
        }
    }

    private Draft draft(List<Step> steps) {
        return new Draft(duration(), TurnDirectorAi.TimeResolution.COMPLETE, "", null, null,
                List.of(), List.of(), List.of(), List.of(), new ResultDraft(steps, DONE));
    }
    private TurnDirectorAi.DurationDraft duration() { return new TurnDirectorAi.DurationDraft(TurnDirectorAi.DurationUnit.SCENE, 1, ""); }
    private Step step(String action, List<TurnDirectorAi.AssetStepDraft> assets) {
        return new Step(action, OrderedTurnResult.Resolution.SUCCEEDED, action + "完成", false, List.of(), List.of(), assets);
    }
    private TurnDirectorAi.AssetStepDraft asset(AssetProcess.Kind kind, List<TurnDirectorAi.FlowDraft> inputs, List<TurnDirectorAi.FlowDraft> outputs) {
        return new TurnDirectorAi.AssetStepDraft(kind, "已发生的资产变化", inputs, outputs, List.of());
    }
    private TurnDirectorAi.FlowDraft flow(String ref, String amount, String unit) { return new TurnDirectorAi.FlowDraft(ref, amount, unit, PLAYER); }
    private StoryStateReducer reducer() {
        AtomicInteger ids = new AtomicInteger();
        return new StoryStateReducer(new StoryPatchValidator(), prefix -> prefix + "-slim-" + ids.incrementAndGet());
    }
    private StoryProjection before(StoryPatch.QuantityValue herbQuantity) {
        var herb = new StoryProjection.EntityState("herb", "ITEM", "药草", "已确认", "已有药草", null, true,
                herbQuantity, true, new StoryPatch.CustodyValue(StoryPatch.HolderType.PLAYER, null), null, null, null);
        var coin = new StoryProjection.EntityState("coin", "ITEM", "铜钱", "已确认", "已有铜钱", null, true,
                new StoryPatch.QuantityValue(true, 20), true, new StoryPatch.CustodyValue(StoryPatch.HolderType.PLAYER, null), null, null, null);
        var teacher = new StoryProjection.EntityState("teacher", "CHARACTER", "药庄先生", "在场", "传授缓息法的人", null, true,
                null, null, null, null, StoryPatch.LifecycleState.ACTIVE, null);
        return new StoryProjection(StoryWorldTime.opening(), new StoryProjection.CoreState("药庄", "平静", "凡俗", "问仙"),
                Map.of("herb", herb, "coin", coin, "teacher", teacher), Map.of(), Map.of(), List.of(),
                StoryTestFixtures.creationContract(), StoryTestFixtures.playPreference(), null, null);
    }
}

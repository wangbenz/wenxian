package com.wenxian.game.story.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.domain.AssetProcess.*;
import com.wenxian.game.story.domain.StoryPatch.*;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.reducer.StoryStateReducer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 验证资产过程的顺序、唯一末态、未知余额和局部引用，不依赖模型或共享数据库。 */
class AssetProcessCompilerTest {
    private static final QuantityValue UNKNOWN = new QuantityValue(false, null);
    private static final StoryTurnDecision.CustodyIntent PLAYER = holder(HolderType.PLAYER, "");
    private static final StoryTurnDecision.CustodyIntent KEEPER = holder(HolderType.CHARACTER, "keeper");
    private final ObjectMapper mapper = new ObjectMapper();
    private final StoryFactIntentCompiler compiler = new StoryFactIntentCompiler();
    private final AtomicInteger ids = new AtomicInteger();
    private final StoryStateReducer reducer = new StoryStateReducer(new StoryPatchValidator(),
            prefix -> prefix + "-asset-test-" + ids.incrementAndGet());

    @Test
    void settledExchangeDebitsGoodsAndAddsIncomeInOneComputedPatch() {
        StoryProjection before = projection(item("herb", 3, PLAYER), item("coin", 20, PLAYER));
        var compiled = compile(before, List.of(), step("sale", Kind.EXCHANGE,
                List.of(flow("herb", 3, PLAYER)), List.of(flow("coin", 50, PLAYER))));
        var applied = reducer.reduce(before, compiled.patch());

        assertEquals(0, applied.projection().entities().get("herb").quantity().value());
        assertEquals(HolderType.NONE, applied.projection().entities().get("herb").custody().holderType());
        assertEquals(70, applied.projection().entities().get("coin").quantity().value());
        assertEquals(20, compiled.process().steps().getFirst().outputs().getFirst().beforeQuantity().value());
        assertEquals(70, compiled.process().steps().getFirst().outputs().getFirst().afterQuantity().value());
        assertEquals(3, before.entities().get("herb").quantity().value(), "编译不得修改冻结前态");
    }

    @Test
    void rejectsSettledExchangeWithoutIncomeAndLeavesTheBeforeStateUnchanged() {
        StoryProjection before = projection(item("herb", 3, PLAYER));
        ProtocolException error = assertThrows(ProtocolException.class, () -> compile(before, List.of(),
                step("sale", Kind.EXCHANGE, List.of(flow("herb", 3, PLAYER)), List.of())));
        assertEquals("INCOMPLETE_PROCESS", error.code());
        assertEquals(3, before.entities().get("herb").quantity().value());
    }

    @Test
    void producedThenConsumedNewItemEndsAtZeroAndProcessGetsFormalIdentity() {
        StoryProjection before = projection(item("herb", 2, PLAYER));
        var compiled = compile(before, List.of(newItem("pill", "疗伤丹")),
                step("brew", Kind.PRODUCE, List.of(flow("herb", 2, PLAYER)), List.of(flow("pill", 1, PLAYER))),
                step("heal", Kind.CONSUME, List.of(flow("pill", 1, PLAYER)), List.of()));
        var applied = reducer.reduce(before, compiled.patch());
        var acceptedProcess = AssetProcess.resolveRefs(compiled.process(), applied.acceptedPatch().entities());
        EntityState pill = applied.projection().entities().values().stream()
                .filter(entity -> entity.name().equals("疗伤丹")).findFirst().orElseThrow();

        assertEquals(0, pill.quantity().value());
        assertEquals(HolderType.NONE, pill.custody().holderType());
        assertEquals(pill.id(), acceptedProcess.steps().getFirst().outputs().getFirst().entityRef());
        assertEquals(pill.id(), acceptedProcess.steps().getLast().inputs().getFirst().entityRef());
        assertEquals(1, acceptedProcess.steps().getLast().inputs().getFirst().beforeQuantity().value());
        assertEquals(0, acceptedProcess.steps().getLast().inputs().getFirst().afterQuantity().value());
    }

    @Test
    void cannotConsumeFutureProductionOrMoreThanCurrentBalance() {
        StoryProjection before = projection(item("herb", 2, PLAYER));
        ProtocolException future = assertThrows(ProtocolException.class, () -> compile(before,
                List.of(newItem("pill", "疗伤丹")),
                step("heal", Kind.CONSUME, List.of(flow("pill", 1, PLAYER)), List.of()),
                step("brew", Kind.PRODUCE, List.of(flow("herb", 2, PLAYER)), List.of(flow("pill", 1, PLAYER)))));
        assertEquals("FUTURE_ASSET", future.code());
        ProtocolException insufficient = assertThrows(ProtocolException.class, () -> compile(before, List.of(),
                step("use", Kind.CONSUME, List.of(flow("herb", 3, PLAYER)), List.of())));
        assertEquals("INSUFFICIENT_QUANTITY", insufficient.code());
    }

    @Test
    void unknownBalanceStaysUnknownUntilExplicitInventoryOrAllConsumption() {
        StoryProjection before = projection(item("herb", UNKNOWN, PLAYER));
        var partial = compile(before, List.of(), step("use", Kind.CONSUME,
                List.of(flow("herb", 2, PLAYER)), List.of()));
        assertEquals(UNKNOWN, partial.patch().entities().getFirst().quantity());
        assertEquals(UNKNOWN, partial.process().steps().getFirst().inputs().getFirst().afterQuantity());

        var all = compile(before, List.of(), step("all", Kind.CONSUME,
                List.of(new Flow("herb", null, true, "份", PLAYER)), List.of()));
        assertEquals(new QuantityValue(true, 0), all.patch().entities().getFirst().quantity());
        assertEquals(UNKNOWN, all.process().steps().getFirst().inputs().getFirst().amount());

        Step inventory = new Step("count", Kind.INVENTORY, "逐份清点剩余药草", List.of(), List.of(),
                List.of(new Observation("herb", new QuantityValue(true, 4), "份", PLAYER, "本轮逐份取出，实际数得四份")));
        var counted = compile(before, List.of(), inventory,
                step("use", Kind.CONSUME, List.of(flow("herb", 1, PLAYER)), List.of()));
        assertEquals(3, counted.patch().entities().getFirst().quantity().value());
    }

    @Test
    void unknownAmountCannotBypassKnownInventoryLimitAndInventoryNeedsEvidence() {
        StoryProjection before = projection(item("herb", 2, PLAYER));
        ProtocolException unknown = assertThrows(ProtocolException.class, () -> compile(before, List.of(),
                step("use", Kind.CONSUME, List.of(new Flow("herb", UNKNOWN, false, "份", PLAYER)), List.of())));
        assertEquals("UNKNOWN_DEDUCTION", unknown.code());
        Step inventory = new Step("count", Kind.INVENTORY, "盘点", List.of(), List.of(),
                List.of(new Observation("herb", new QuantityValue(true, 9), "份", PLAYER, "")));
        assertEquals("OBSERVATION_EVIDENCE", assertThrows(ProtocolException.class,
                () -> compile(before, List.of(), inventory)).code());
    }

    @Test
    void partialTransferPreservesKeeperRemainderAndCreatesPlayerStack() {
        StoryProjection before = projection(character("keeper"), item("herb", 5, KEEPER));
        var compiled = compile(before, List.of(newItem("gift", "草药")),
                step("deliver", Kind.TRANSFER, List.of(flow("herb", 2, KEEPER)), List.of(flow("gift", 2, PLAYER))));
        var applied = reducer.reduce(before, compiled.patch());
        EntityState source = applied.projection().entities().get("herb");
        EntityState target = applied.projection().entities().values().stream()
                .filter(entity -> entity.id().startsWith("item-asset-test-")).findFirst().orElseThrow();
        assertEquals(3, source.quantity().value());
        assertEquals("keeper", source.custody().holderRef());
        assertEquals(2, target.quantity().value());
        assertEquals(HolderType.PLAYER, target.custody().holderType());
    }

    @Test
    void fullTransferMayKeepIdentityButPartialTransferCannotMoveWholeStack() {
        StoryProjection before = projection(character("keeper"), item("herb", 5, KEEPER));
        assertEquals("PARTIAL_STACK_TRANSFER", assertThrows(ProtocolException.class, () -> compile(before, List.of(),
                step("deliver", Kind.TRANSFER, List.of(flow("herb", 2, KEEPER)), List.of(flow("herb", 2, PLAYER))))).code());
        var full = compile(before, List.of(), step("deliver", Kind.TRANSFER,
                List.of(new Flow("herb", null, true, "份", KEEPER)), List.of(flow("herb", 5, PLAYER))));
        var applied = reducer.reduce(before, full.patch());
        assertEquals(5, applied.projection().entities().get("herb").quantity().value());
        assertEquals(HolderType.PLAYER, applied.projection().entities().get("herb").custody().holderType());
    }

    @Test
    void transferCannotChangeAmountOrMergeDistinctKnownStacksByName() {
        StoryProjection before = projection(character("keeper"), item("herb", 5, KEEPER), item("other", 1, PLAYER));
        assertEquals("TRANSFER_QUANTITY", assertThrows(ProtocolException.class, () -> compile(before,
                List.of(newItem("gift", "草药")), step("deliver", Kind.TRANSFER,
                        List.of(flow("herb", 2, KEEPER)), List.of(flow("gift", 3, PLAYER))))).code());
        assertEquals("IMPLICIT_STACK_MERGE", assertThrows(ProtocolException.class, () -> compile(before, List.of(),
                step("deliver", Kind.TRANSFER, List.of(flow("herb", 2, KEEPER)), List.of(flow("other", 2, PLAYER))))).code());
    }

    @Test
    void rejectsWrongCustodyUnknownIdentityDuplicateStepsAndOverflow() {
        StoryProjection before = projection(character("keeper"), item("herb", 5, KEEPER), item("coin", Integer.MAX_VALUE, PLAYER));
        assertEquals("SOURCE_CUSTODY", assertThrows(ProtocolException.class, () -> compile(before, List.of(),
                step("use", Kind.CONSUME, List.of(flow("herb", 1, PLAYER)), List.of()))).code());
        assertEquals("UNKNOWN_ITEM", assertThrows(ProtocolException.class, () -> compile(before, List.of(),
                step("gain", Kind.ACQUIRE, List.of(), List.of(flow("fake-id", 1, PLAYER))))).code());
        Step use = step("use", Kind.CONSUME, List.of(flow("herb", 1, KEEPER)), List.of());
        assertEquals("DUPLICATE", assertThrows(ProtocolException.class,
                () -> compile(before, List.of(), use, use)).code());
        assertEquals("OVERFLOW", assertThrows(ProtocolException.class, () -> compile(before, List.of(),
                step("gain", Kind.ACQUIRE, List.of(), List.of(flow("coin", 1, PLAYER))))).code());
    }

    @Test
    void enforcesPerTurnUnitAndPersistsOnlyTheServerAcceptedCountingUnit() {
        StoryProjection before = projection(item("herb", 5, PLAYER));
        Step use = step("use", Kind.CONSUME, List.of(flow("herb", 1, PLAYER)), List.of());
        Step otherUnit = step("use2", Kind.CONSUME,
                List.of(new Flow("herb", new QuantityValue(true, 1), false, "斤", PLAYER)), List.of());
        assertEquals("UNIT_MISMATCH", assertThrows(ProtocolException.class,
                () -> compile(before, List.of(), use, otherUnit)).code());
        var compiled = compile(before, List.of(), use);
        assertEquals("份", compiled.patch().entities().getFirst().attributes().path("quantityUnit").asText());
        assertEquals("份", compiled.process().steps().getFirst().inputs().getFirst().unit());

        EntityState herb = item("herb", 5, PLAYER);
        EntityState specified = new EntityState(herb.id(), herb.entityType(), herb.name(), herb.status(), herb.summary(),
                mapper.createObjectNode().put("quantityUnit", "斤"), true, herb.quantity(), true, herb.custody(), null, null, null);
        assertEquals("UNIT_MISMATCH", assertThrows(ProtocolException.class,
                () -> compile(projection(specified), List.of(), use)).code());
    }

    @Test
    void eachProcessKindReportsItsActualInvalidFieldInsteadOfGenericExchangeMessage() {
        Flow herb = flow("herb", 1, PLAYER), coin = flow("coin", 1, PLAYER);
        Observation observation = new Observation("herb", new QuantityValue(true, 1), "份", PLAYER, "本轮核对");
        List<Step> invalid = List.of(
                step("bad", Kind.ACQUIRE, List.of(herb), List.of(coin)),
                step("bad", Kind.PRODUCE, List.of(herb), List.of()),
                step("bad", Kind.CONSUME, List.of(herb), List.of(coin)),
                step("bad", Kind.TRANSFER, List.of(herb, coin), List.of(coin)),
                step("bad", Kind.EXCHANGE, List.of(herb), List.of()),
                new Step("bad", Kind.INVENTORY, "核对", List.of(herb), List.of(), List.of(observation)));
        List<String> fields = List.of("inputs", "outputs", "outputs", "inputs", "outputs", "inputs");
        for (int index = 0; index < invalid.size(); index++) {
            Step malformed = invalid.get(index);
            ProtocolException error = assertThrows(ProtocolException.class,
                    () -> compile(projection(), List.of(), malformed));
            assertEquals("assetProcess.steps[0]." + fields.get(index), error.path());
            assertTrue(error.getMessage().contains(malformed.kind().name()));
            if (malformed.kind() != Kind.EXCHANGE) assertFalse(error.getMessage().contains("已声明结算"));
        }
        for (Kind kind : List.of(Kind.ACQUIRE, Kind.PRODUCE, Kind.CONSUME, Kind.TRANSFER, Kind.EXCHANGE)) {
            Step malformed = new Step("bad", kind, "已发生", List.of(herb), List.of(coin), List.of(observation));
            ProtocolException error = assertThrows(ProtocolException.class,
                    () -> compile(projection(), List.of(), malformed));
            assertEquals("assetProcess.steps[0].observations", error.path());
            assertTrue(error.getMessage().contains(kind + " 的 observations 必须为空数组"));
        }
    }

    @Test
    void reportsC11ForbiddenObservationsAndAllAmountConflictsAcrossStepsTogether() {
        Flow contradictory = new Flow("herb", new QuantityValue(true, 1), true, "份", PLAYER);
        Observation finalBalance = new Observation("herb", new QuantityValue(true, 0), "份", PLAYER, "重复末态");
        Step production = new Step("brew", Kind.PRODUCE, "炼药", List.of(contradictory),
                List.of(flow("pill", 1, PLAYER)), List.of(finalBalance));
        Step use = new Step("use", Kind.CONSUME, "用药", List.of(contradictory), List.of(), List.of(finalBalance));
        ProtocolException error = assertThrows(ProtocolException.class,
                () -> compile(projection(), List.of(), production, use));
        assertEquals("assetProcess.steps[0].observations", error.path());
        for (String field : List.of("steps[0].observations", "steps[0].inputs[0].amount",
                "steps[1].observations", "steps[1].inputs[0].amount")) {
            assertTrue(error.getMessage().contains(field), "一次修复必须获得全部独立形状错误：" + field);
        }
        assertTrue(error.getMessage().contains("amount 必须为 null"));
        assertFalse(error.getMessage().contains("已声明结算"));

        Step invalidOutput = step("gain", Kind.ACQUIRE, List.of(),
                List.of(new Flow("pill", null, true, "粒", PLAYER)));
        ProtocolException output = assertThrows(ProtocolException.class,
                () -> compile(projection(), List.of(), invalidOutput));
        assertEquals("assetProcess.steps[0].outputs[0].all", output.path());
        assertEquals("ALL_DIRECTION", output.code());
    }

    @Test
    void publicShapePrecheckNeedsNoProjectionAndUsesExactlyTheCompilationDiagnostics() {
        AssetProcessCompiler assets = new AssetProcessCompiler();
        Step otherwiseValid = step("gain", Kind.ACQUIRE, List.of(), List.of(flow("unresolved-new-item", 1, PLAYER)));
        assertDoesNotThrow(() -> assets.validateShape(new Proposal(AssetProcess.VERSION, List.of(otherwiseValid))),
                "形状预检不得假装已经解析未知实体或完成领域校验");
        assertEquals("UNKNOWN_ITEM", assertThrows(ProtocolException.class,
                () -> compile(projection(), List.of(), otherwiseValid)).code());

        Step malformed = new Step("brew", Kind.PRODUCE, "炼药",
                List.of(new Flow("herb", new QuantityValue(true, 1), true, "份", PLAYER)),
                List.of(flow("pill", 1, PLAYER)),
                List.of(new Observation("herb", new QuantityValue(true, 0), "份", PLAYER, "重复填写末态")));
        Proposal proposal = new Proposal(AssetProcess.VERSION, List.of(malformed));
        ProtocolException precheck = assertThrows(ProtocolException.class, () -> assets.validateShape(proposal));
        ProtocolException compilation = assertThrows(ProtocolException.class,
                () -> compile(projection(), List.of(), malformed));
        assertEquals(compilation.getMessage(), precheck.getMessage());
        assertTrue(precheck.getMessage().contains("steps[0].observations"));
        assertTrue(precheck.getMessage().contains("steps[0].inputs[0].amount"));
        assertSame(malformed, proposal.steps().getFirst(), "预检不能改写被拒绝的模型原提案");
        assertTrue(proposal.steps().getFirst().inputs().getFirst().all());
        assertEquals(1, proposal.steps().getFirst().inputs().getFirst().amount().value());
    }

    @Test
    void reloadedQuantityUnitCannotChangeAndAttributeDeltaPreservesExistingMetadata() throws Exception {
        EntityState coin = item("coin", 20, PLAYER);
        EntityState described = new EntityState(coin.id(), coin.entityType(), coin.name(), coin.status(), coin.summary(),
                mapper.createObjectNode().put("origin", "旧钱袋"), true, coin.quantity(), true, coin.custody(), null, null, null);
        var metadata = new StoryTurnDecision.EntityIntent("coin", "", "ITEM", "", "", "",
                mapper.createObjectNode().put("condition", "干燥"), null, null, null, null, null, true);
        Step gain = step("gain", Kind.ACQUIRE, List.of(),
                List.of(new Flow("coin", new QuantityValue(true, 50), false, "文", PLAYER)));
        StoryProjection after = reducer.reduce(projection(described), compile(projection(described), List.of(metadata), gain).patch()).projection();
        EntityState persisted = after.entities().get("coin");
        assertEquals("旧钱袋", persisted.attributes().path("origin").asText());
        assertEquals("干燥", persisted.attributes().path("condition").asText());
        assertEquals("文", persisted.attributes().path("quantityUnit").asText());

        // 用序列化后的属性构造下一轮投影，验证口径来自保存数据，而不是上次编译器的局部变量。
        EntityState reloaded = new EntityState(persisted.id(), persisted.entityType(), persisted.name(), persisted.status(),
                persisted.summary(), mapper.readTree(mapper.writeValueAsString(persisted.attributes())), persisted.visible(),
                persisted.quantity(), persisted.inPossession(), persisted.custody(), null, null, null);
        Step spendWrongUnit = step("spend", Kind.CONSUME,
                List.of(new Flow("coin", new QuantityValue(true, 12), false, "两", PLAYER)), List.of());
        assertEquals("UNIT_MISMATCH", assertThrows(ProtocolException.class,
                () -> compile(projection(reloaded), List.of(), spendWrongUnit)).code());
        Step spendInheritedUnit = step("spend", Kind.CONSUME,
                List.of(new Flow("coin", new QuantityValue(true, 12), false, "", PLAYER)), List.of());
        var spent = compile(projection(reloaded), List.of(), spendInheritedUnit);
        assertEquals(58, spent.patch().entities().getFirst().quantity().value());
        assertEquals("文", spent.process().steps().getFirst().inputs().getFirst().unit());
    }

    @Test
    void reacquiringHiddenEmptyStackRestoresVisibilityAndCorrectCustody() {
        EntityState empty = new EntityState("coin", "ITEM", "铜钱", "已用完", "旧钱袋", null, false,
                new QuantityValue(true, 0), false, new CustodyValue(HolderType.NONE, null), null, null, null);
        StoryProjection before = projection(empty);
        Step gain = step("gain", Kind.ACQUIRE, List.of(),
                List.of(new Flow("coin", new QuantityValue(true, 50), false, "文", PLAYER)));
        var compiled = compile(before, List.of(), gain);
        assertEquals(EntityOperation.RESTORE, compiled.patch().entities().getFirst().operation());
        EntityState restored = reducer.reduce(before, compiled.patch()).projection().entities().get("coin");
        assertTrue(restored.visible());
        assertTrue(restored.inPossession());
        assertEquals(50, restored.quantity().value());
        assertEquals(HolderType.PLAYER, restored.custody().holderType());
    }

    @Test
    void nonexistentHistoricalStackCannotBeConsumedOrSoldEvenIfLegacyQuantityIsPositive() {
        var none = holder(HolderType.NONE, "");
        EntityState legacy = new EntityState("herb", "ITEM", "草药", "不再存在", "旧记录", null, false,
                new QuantityValue(true, 5), false, new CustodyValue(HolderType.NONE, null), null, null, null);
        StoryProjection before = projection(legacy, item("coin", 1, PLAYER));
        for (Step step : List.of(
                step("use", Kind.CONSUME, List.of(flow("herb", 1, none)), List.of()),
                step("sell", Kind.EXCHANGE, List.of(flow("herb", 1, none)), List.of(flow("coin", 5, PLAYER))))) {
            assertEquals("NONEXISTENT_SOURCE", assertThrows(ProtocolException.class,
                    () -> compile(before, List.of(), step)).code());
        }
    }

    @Test
    void newProtocolRejectsIndependentFinalValuesAndUnexplainedNewItems() {
        StoryTurnDecision.EntityIntent item = new StoryTurnDecision.EntityIntent("", "pill", "ITEM", "丹药",
                "持有", "新物品", null, new QuantityValue(true, 1), PLAYER, null, null, null, true);
        assertEquals("FINAL_ASSET_WRITE", assertThrows(ProtocolException.class,
                () -> compile(projection(), List.of(item))).code());
        assertEquals("MISSING_ASSET_PROCESS", assertThrows(ProtocolException.class,
                () -> compile(projection(), List.of(newItem("pill", "丹药")))).code());
    }

    @Test
    void missingProcessStepsAndMetadataCannotSilentlyBecomeNoAssetChange() {
        var base = new StoryTurnDecision(new TurnOutcome(OutcomeResolution.SUCCEEDED, "行动完成"),
                new SemanticDuration(SemanticDuration.Unit.PHASE, 1, ""), TimeResolution.COMPLETE,
                "", null, null, List.of(), List.of(), List.of(), null, List.of(),
                new Proposal(AssetProcess.VERSION, null), null);
        assertEquals("REQUIRED", assertThrows(ProtocolException.class,
                () -> compiler.compileDetailed(projection(), base)).code());

        StoryProjection before = projection(item("herb", 3, PLAYER));
        var shadow = new StoryTurnDecision.EntityIntent("herb", "", "ITEM", "", "", "",
                mapper.createObjectNode().put("balance", 100), null, null, null, null, null, true);
        assertEquals("RESERVED_ASSET_FIELD", assertThrows(ProtocolException.class,
                () -> compile(before, List.of(shadow))).code());
        var hidden = new StoryTurnDecision.EntityIntent("herb", "", "ITEM", "", "", "",
                null, null, null, null, null, null, false);
        assertEquals("HIDE_NONEMPTY_STACK", assertThrows(ProtocolException.class,
                () -> compile(before, List.of(hidden))).code());
    }

    @Test
    void inventoryCannotOverwriteKnownBalanceCreateAssetsOrReplaceKnownCustody() {
        StoryProjection before = projection(character("keeper"), item("herb", 1, PLAYER));
        Step consume = step("use", Kind.CONSUME, List.of(flow("herb", 1, PLAYER)), List.of());
        Step replenishByCounting = new Step("count", Kind.INVENTORY, "清点药材", List.of(), List.of(),
                List.of(new Observation("herb", new QuantityValue(true, 1), "份", PLAYER, "声称清点发现一份")));
        assertEquals("INVENTORY_OVERWRITE", assertThrows(ProtocolException.class,
                () -> compile(before, List.of(), consume, replenishByCounting)).code());

        Step eraseKnown = new Step("count", Kind.INVENTORY, "未数清", List.of(), List.of(),
                List.of(new Observation("herb", UNKNOWN, "份", PLAYER, "再次查看但没有数清")));
        assertEquals("INVENTORY_OVERWRITE", assertThrows(ProtocolException.class,
                () -> compile(before, List.of(), eraseKnown)).code());

        Step createByCounting = new Step("count", Kind.INVENTORY, "清点新药", List.of(), List.of(),
                List.of(new Observation("pill", new QuantityValue(true, 1), "份", PLAYER, "声称已有一份")));
        assertEquals("UNESTABLISHED_INVENTORY", assertThrows(ProtocolException.class,
                () -> compile(before, List.of(newItem("pill", "丹药")), createByCounting)).code());

        Step movedByCounting = new Step("count", Kind.INVENTORY, "核对药材", List.of(), List.of(),
                List.of(new Observation("herb", new QuantityValue(true, 1), "份", KEEPER, "声称现在由守药人保管")));
        assertEquals("INVENTORY_CUSTODY_OVERWRITE", assertThrows(ProtocolException.class,
                () -> compile(before, List.of(), movedByCounting)).code());

        var verified = compile(before, List.of(), replenishByCounting);
        assertEquals(1, verified.patch().entities().getFirst().quantity().value());
    }

    @Test
    void inventoryMayClarifyUnknownQuantityAndCustodyWithoutInventingOldZero() {
        StoryProjection before = projection(character("keeper"), item("herb", UNKNOWN, holder(HolderType.UNKNOWN, "")));
        Step inventory = new Step("count", Kind.INVENTORY, "现场核实药材", List.of(), List.of(),
                List.of(new Observation("herb", new QuantityValue(true, 2), "份", KEEPER, "现场数得两份，确认守药人正在保管")));
        var compiled = compile(before, List.of(), inventory);
        assertEquals(UNKNOWN, compiled.process().steps().getFirst().observations().getFirst().beforeQuantity());
        assertEquals(2, compiled.patch().entities().getFirst().quantity().value());
        assertEquals("keeper", compiled.patch().entities().getFirst().custody().holderRef());
    }

    @Test
    void newCharacterCustodyUsesOneServerOwnedIdentityInProjectionAndAcceptedProcess() {
        StoryTurnDecision.EntityIntent keeper = new StoryTurnDecision.EntityIntent("", "new-keeper", "CHARACTER",
                "守药人", "在场", "本轮初识", null, null, null, null, null, null, true);
        var custody = new StoryTurnDecision.CustodyIntent(HolderType.CHARACTER, "", "new-keeper");
        var compiled = compile(projection(), List.of(keeper, newItem("medicine", "药材")),
                step("observe", Kind.ACQUIRE, List.of(), List.of(flow("medicine", 2, custody))));
        var applied = reducer.reduce(projection(), compiled.patch());
        var accepted = AssetProcess.resolveRefs(compiled.process(), applied.acceptedPatch().entities());
        String keeperId = applied.projection().entities().values().stream()
                .filter(entity -> entity.entityType().equals("CHARACTER")).findFirst().orElseThrow().id();
        assertEquals(keeperId, accepted.steps().getFirst().outputs().getFirst().afterCustody().holderRef());
        assertThrows(IllegalStateException.class, () -> AssetProcess.resolveRefs(compiled.process(), List.of()));
    }

    private StoryFactIntentCompiler.Compilation compile(StoryProjection before,
            List<StoryTurnDecision.EntityIntent> metadata, Step... steps) {
        StoryTurnDecision decision = new StoryTurnDecision(
                new TurnOutcome(OutcomeResolution.SUCCEEDED, "行动已形成明确结果"),
                new SemanticDuration(SemanticDuration.Unit.PHASE, 1, ""), TimeResolution.COMPLETE,
                "", null, null, metadata, List.of(), List.of(), null, List.of(),
                new Proposal(AssetProcess.VERSION, List.of(steps)), null);
        return compiler.compileDetailed(before, decision);
    }

    private Step step(String id, Kind kind, List<Flow> inputs, List<Flow> outputs) {
        return new Step(id, kind, "本步骤已发生", inputs, outputs, List.of());
    }

    private static Flow flow(String ref, int amount, StoryTurnDecision.CustodyIntent custody) {
        return new Flow(ref, new QuantityValue(true, amount), false, "份", custody);
    }

    private StoryTurnDecision.EntityIntent newItem(String ref, String name) {
        return new StoryTurnDecision.EntityIntent("", ref, "ITEM", name, "已确认", "本轮明确的物品",
                null, null, null, null, null, null, true);
    }

    private EntityState item(String id, int quantity, StoryTurnDecision.CustodyIntent custody) {
        return item(id, new QuantityValue(true, quantity), custody);
    }

    private EntityState item(String id, QuantityValue quantity, StoryTurnDecision.CustodyIntent custody) {
        return new EntityState(id, "ITEM", "草药", "已确认", "已有物品", null, true,
                quantity, custody.holderType() == HolderType.PLAYER,
                new CustodyValue(custody.holderType(), custody.holderRef().isBlank() ? null : custody.holderRef()),
                null, null, null);
    }

    private EntityState character(String id) {
        return new EntityState(id, "CHARACTER", "守药人", "在场", "已确认的人物", null, true,
                null, null, null, null, LifecycleState.ACTIVE, null);
    }

    private static StoryTurnDecision.CustodyIntent holder(HolderType type, String ref) {
        return new StoryTurnDecision.CustodyIntent(type, ref, "");
    }

    private StoryProjection projection(EntityState... entities) {
        Map<String, EntityState> map = java.util.Arrays.stream(entities)
                .collect(java.util.stream.Collectors.toMap(EntityState::id, entity -> entity));
        return new StoryProjection(StoryWorldTime.opening(),
                new StoryProjection.CoreState("客栈", "平静", "凡俗", "问仙"), map, Map.of(), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(), null, null);
    }
}

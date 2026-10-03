package com.wenxian.game.story.domain;

import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.domain.OrderedTurnResult.Accepted;
import com.wenxian.game.story.domain.OrderedTurnResult.AcceptedStep;
import com.wenxian.game.story.domain.OrderedTurnResult.Dependency;
import com.wenxian.game.story.domain.OrderedTurnResult.Expected;
import com.wenxian.game.story.domain.OrderedTurnResult.Proposal;
import com.wenxian.game.story.domain.OrderedTurnResult.ProtocolException;
import com.wenxian.game.story.domain.OrderedTurnResult.Resolution;
import com.wenxian.game.story.domain.OrderedTurnResult.Statement;
import com.wenxian.game.story.domain.OrderedTurnResult.Step;
import com.wenxian.game.story.domain.OrderedTurnResult.Stop;
import com.wenxian.game.story.domain.OrderedTurnResult.StopReason;
import com.wenxian.game.story.reducer.StoryStateReducer;
import com.wenxian.game.story.support.StoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 只用合成输入验证子结果、修行裁决、资产归属和派生记忆；不调用模型或数据库。 */
class OrderedTurnResultCompilerTest {
    private final StoryFactIntentCompiler compiler = new StoryFactIntentCompiler();
    private final AtomicInteger ids = new AtomicInteger();
    private final StoryStateReducer reducer = new StoryStateReducer(new StoryPatchValidator(),
            prefix -> prefix + "-ordered-" + ids.incrementAndGet());

    @Test
    void acceptedBreakthroughAppendsRealmResultAndPreservesExperienceAndFailedVisit() {
        StoryProjection before = projection(WorldPackage.RealmPhase.PERFECT, 1, 2, Map.of());
        StoryTurnDecision draft = decision(List.of(cultivation(Resolution.SUCCEEDED),
                ordinary("访友", Resolution.FAILED, "友人已经出门，未能见面")), false, true);
        StoryFactIntentCompiler.Compilation result = compiler.compileDetailed(before, draft);
        StoryStateReducer.Applied applied = reducer.reduce(before, result.patch());

        assertEquals(StoryPatch.OutcomeResolution.PARTIAL, result.patch().outcome().resolution());
        assertEquals(Resolution.SUCCEEDED, result.acceptedResult().steps().getFirst().resolution());
        assertEquals(Resolution.FAILED, result.acceptedResult().steps().get(1).resolution());
        assertEquals("stage-03", applied.projection().protagonistCultivation().currentStageId());
        String canonical = result.acceptedResult().steps().getFirst().realmResult().result();
        assertTrue(canonical.contains("筑基初期"));
        assertFalse(canonical.contains("stage-03"));
        assertEquals(canonical, applied.projection().protagonistCultivation().progressNote());
        assertTrue(result.patch().outcome().summary().contains("未能见面"));
        assertTrue(result.patch().outcome().summary().contains("调息后气息逐渐稳定"));
        assertEquals(result.acceptedResult().memories(), result.patch().memories());
        assertEquals(1, result.patch().memories().size());
    }

    @Test
    void resolvedStopCannotReintroduceRejectedBreakthroughConclusionIntoSummaryOrMemory() {
        StoryProjection before = projection(WorldPackage.RealmPhase.PERFECT, 1, 2, Map.of());
        List<Step> steps = List.of(cultivation(Resolution.SUCCEEDED),
                ordinary("访友", Resolution.FAILED, "友人已经出门，未能见面"));
        String obsolete = "突破未成但获得感悟，此后访友结束";
        StoryTurnDecision draft = replaceProposal(decision(steps, false, true),
                new Proposal(steps, new Stop(StopReason.ACTION_RESOLVED, obsolete)));
        StoryFactIntentCompiler.Compilation compiled = compiler.compileDetailed(before, draft);
        Accepted accepted = compiled.acceptedResult();

        assertEquals("本轮行动结束于以上已记录的结果。", accepted.stop().detail());
        assertEquals(Resolution.SUCCEEDED, accepted.steps().getFirst().resolution());
        assertEquals(Resolution.FAILED, accepted.steps().get(1).resolution());
        assertTrue(accepted.summary().contains("筑基初期"));
        assertTrue(accepted.summary().contains("未能见面"));
        assertFalse(accepted.summary().contains("突破未成"));
        assertTrue(compiled.patch().memories().stream().noneMatch(memory -> memory.summary().contains("突破未成")));
        assertEquals(obsolete, draft.orderedResult().stop().detail(), "原模型草案只留作诊断，不能被伪造为已经确认");
    }

    @Test
    void changedCultivationRequiresRecheckingBlockedAndDecisionStopEvenWithoutDeclaredDependency() {
        StoryProjection before = projection(WorldPackage.RealmPhase.PERFECT, 1, 2, Map.of());
        List<Step> steps = List.of(cultivation(Resolution.FAILED));
        for (StopReason stop : List.of(StopReason.BLOCKED, StopReason.DECISION_REQUIRED)) {
            StoryTurnDecision draft = replaceProposal(decision(steps, false, true),
                    new Proposal(steps, new Stop(stop, "突破未成，是否再次服丹尝试")));
            ProtocolException rejected = assertThrows(ProtocolException.class,
                    () -> compiler.compileDetailed(before, draft));
            assertEquals("CULTIVATION_RESULT_CHANGED", rejected.code());
            assertEquals("orderedResult.steps[0].resolution", rejected.path());
            assertTrue(rejected.getMessage().contains("索引=0"));
            assertTrue(rejected.getMessage().contains("RANK_ADVANCED"));
        }
    }

    @Test
    void unchangedCultivationKeepsTheConcreteDecisionQuestion() {
        StoryProjection before = projection(WorldPackage.RealmPhase.PERFECT, 1, 2, Map.of());
        List<Step> steps = List.of(cultivation(Resolution.SUCCEEDED),
                ordinary("决定是否远行", Resolution.NOT_EXECUTED, "友人的远行邀约尚未接受"));
        String question = "是否接受与友人同行三个月的邀约";
        StoryTurnDecision draft = replaceProposal(decision(steps, false, true),
                new Proposal(steps, new Stop(StopReason.DECISION_REQUIRED, question)));
        StoryFactIntentCompiler.Compilation compiled = compiler.compileDetailed(before, draft);
        assertEquals(question, compiled.acceptedResult().stop().detail());
        assertEquals(question, compiled.patch().decisionCheckpoint().prompt());
        assertEquals(StoryPatch.OutcomeResolution.PARTIAL, compiled.patch().outcome().resolution());
    }

    @Test
    void breakthroughThenHealingKeepsTheLaterConsumptionAndFinalCondition() {
        StoryProjection.EntityState pill = new StoryProjection.EntityState("pill-one", "ITEM", "回春丹",
                "随身", "治疗用药", null, true, new StoryPatch.QuantityValue(true, 2), true,
                new StoryPatch.CustodyValue(StoryPatch.HolderType.PLAYER, null), null, null);
        StoryProjection before = projection(WorldPackage.RealmPhase.PERFECT, 1, 2, Map.of(pill.id(), pill));
        Step heal = new Step("救治友人", Resolution.SUCCEEDED, "用一枚回春丹稳定了友人的伤势", false,
                List.of(0), List.of(new Dependency(0, Expected.SUCCEEDED)), List.of());
        AssetProcess.Proposal process = new AssetProcess.Proposal(1, List.of(new AssetProcess.Step("step-1",
                AssetProcess.Kind.CONSUME, "救治时服用一枚丹药", List.of(new AssetProcess.Flow(pill.id(),
                new StoryPatch.QuantityValue(true, 1), false, "枚",
                new StoryTurnDecision.CustodyIntent(StoryPatch.HolderType.PLAYER, null, null))), List.of(), List.of())));
        StoryTurnDecision draft = decision(List.of(cultivation(Resolution.SUCCEEDED), heal), false, true,
                process, List.of(), new StoryTurnDecision.CoreStateIntent("友人住处", "气息稳固", null));
        StoryFactIntentCompiler.Compilation compiled = compiler.compileDetailed(before, draft);
        StoryStateReducer.Applied applied = reducer.reduce(before, compiled.patch());

        assertEquals("stage-03", applied.projection().protagonistCultivation().currentStageId());
        assertEquals(1, applied.projection().entities().get(pill.id()).quantity().value());
        assertEquals("友人住处", applied.projection().coreState().location());
        assertTrue(compiled.patch().outcome().summary().contains("稳定了友人的伤势"));
        assertEquals(List.of(0), compiled.acceptedResult().steps().get(1).assetStepIndexes());
    }

    @Test
    void changedBreakthroughPremiseRejectsTheWholeCandidateWithRuleFeedback() {
        Step branch = new Step("失败后求医", Resolution.SUCCEEDED, "已经抵达医馆", false,
                List.of(), List.of(new Dependency(0, Expected.FAILED)), List.of());
        StoryTurnDecision draft = decision(List.of(cultivation(Resolution.FAILED), branch), false, true,
                emptyProcess(), List.of(), new StoryTurnDecision.CoreStateIntent("医馆", null, null));
        ProtocolException error = assertThrows(ProtocolException.class, () -> compiler.compileDetailed(
                projection(WorldPackage.RealmPhase.PERFECT, 1, 2, Map.of()), draft));

        assertEquals("CULTIVATION_RESULT_CHANGED", error.code());
        assertTrue(error.getMessage().contains("索引=0"));
        assertTrue(error.getMessage().contains("SUCCEEDED"));
        assertTrue(error.getMessage().contains("RANK_ADVANCED"));
        assertEquals(Resolution.FAILED, draft.orderedResult().steps().getFirst().resolution());
        assertEquals("医馆", draft.coreState().location(), "原草案不被局部删除或伪造为已经修复");
    }

    @Test
    void newlyEligibleUnexecutedBranchAlsoRequiresReplanning() {
        Step skipped = new Step("成功后访友", Resolution.NOT_EXECUTED, "原先认为突破未成，尚未出门", false,
                List.of(), List.of(new Dependency(0, Expected.SUCCEEDED)), List.of());
        assertThrows(ProtocolException.class, () -> compiler.compileDetailed(
                projection(WorldPackage.RealmPhase.PERFECT, 1, 2, Map.of()),
                decision(List.of(cultivation(Resolution.FAILED), skipped), false, true)));
    }

    @Test
    void unrelatedSuccessCannotAuthorizeFailedCultivationProgress() {
        StoryProjection before = projection(WorldPackage.RealmPhase.EARLY, 0, 2, Map.of());
        StoryFactIntentCompiler.Compilation compiled = compiler.compileDetailed(before,
                decision(List.of(cultivation(Resolution.FAILED),
                        ordinary("访友", Resolution.SUCCEEDED, "与友人相见")), true, false));
        StoryStateReducer.Applied applied = reducer.reduce(before, compiled.patch());
        assertEquals(StoryPatch.OutcomeResolution.PARTIAL, compiled.patch().outcome().resolution());
        assertEquals(StoryPatch.OutcomeResolution.FAILED, compiled.patch().cultivation().actionOutcome());
        assertEquals(0, applied.projection().protagonistCultivation().progressMarks());
    }

    @Test
    void unrelatedFailureDoesNotVetoSuccessfulCultivationProgress() {
        StoryProjection before = projection(WorldPackage.RealmPhase.EARLY, 0, 2, Map.of());
        StoryFactIntentCompiler.Compilation compiled = compiler.compileDetailed(before,
                decision(List.of(cultivation(Resolution.SUCCEEDED),
                        ordinary("访友", Resolution.FAILED, "未找到友人")), true, false));
        StoryStateReducer.Applied applied = reducer.reduce(before, compiled.patch());
        assertEquals(StoryPatch.OutcomeResolution.PARTIAL, compiled.patch().outcome().resolution());
        assertEquals(StoryPatch.OutcomeResolution.SUCCEEDED, compiled.patch().cultivation().actionOutcome());
        assertEquals(1, applied.projection().protagonistCultivation().progressMarks());
    }

    @Test
    void partialBreakthroughPreparationMatchesOnlyPartialDependencies() {
        StoryProjection before = projection(WorldPackage.RealmPhase.PERFECT, 0, 3, Map.of());
        Step follow = new Step("准备尚未完成时休整", Resolution.SUCCEEDED, "结束吐纳并休息", false,
                List.of(), List.of(new Dependency(0, Expected.PARTIAL)), List.of());
        StoryFactIntentCompiler.Compilation compiled = compiler.compileDetailed(before,
                decision(List.of(cultivation(Resolution.PARTIAL), follow), true, true));
        assertEquals(Resolution.PARTIAL, compiled.acceptedResult().steps().getFirst().resolution());
        assertEquals(StoryPatch.OutcomeResolution.PARTIAL, compiled.patch().cultivation().actionOutcome());
        assertTrue(compiled.acceptedResult().steps().getFirst().realmResult().result().contains("尚未跨越大境界"));
        assertEquals(1, reducer.reduce(before, compiled.patch()).projection().protagonistCultivation().progressMarks());
        Step wrong = new Step(follow.action(), follow.resolution(), follow.result(), false, List.of(),
                List.of(new Dependency(0, Expected.FAILED)), List.of());
        assertThrows(ProtocolException.class, () -> compiler.compileDetailed(before,
                decision(List.of(cultivation(Resolution.PARTIAL), wrong), true, true)));
    }

    @Test
    void priceConditionCanSkipBuyingWithoutAnArtificialResultDependency() {
        Step quote = new Step("询价", Resolution.SUCCEEDED, "掌柜报出价格", false, List.of(), List.of(),
                List.of(new Statement(null, "路边掌柜", "这只药囊售价十二文。")));
        Step skip = ordinary("十文以内购买", Resolution.NOT_EXECUTED, "报价十二文超过十文上限，未购买");
        StoryFactIntentCompiler.Compilation compiled = compiler.compileDetailed(base(),
                decision(List.of(quote, skip), false, false));
        assertEquals(StoryPatch.OutcomeResolution.SUCCEEDED, compiled.patch().outcome().resolution());
        assertTrue(compiled.patch().outcome().summary().contains(skip.result()));
        assertFalse(compiled.patch().outcome().summary().contains("前序条件未满足"));
        assertEquals(2, compiled.patch().memories().size());
        assertEquals("CLUE", compiled.patch().memories().get(1).memoryType());
        assertTrue(compiled.patch().memories().get(1).summary().contains("路边掌柜当时表示：这只药囊售价十二文。"));
        assertTrue(compiled.patch().worldFacts().isEmpty());
        assertTrue(compiled.patch().entities().isEmpty());
    }

    @Test
    void decisionStopAndUnexecutedReasonSurviveInTheSameSummary() {
        Step skipped = ordinary("签约购买", Resolution.NOT_EXECUTED, "掌柜新增长期供货义务，尚未签约");
        StoryTurnDecision draft = decision(List.of(skipped), false, false);
        draft = replaceProposal(draft, new Proposal(List.of(skipped),
                new Stop(StopReason.DECISION_REQUIRED, "是否接受一年供货的承诺")));
        StoryFactIntentCompiler.Compilation compiled = compiler.compileDetailed(base(), draft);
        assertTrue(compiled.patch().decisionCheckpoint().required());
        assertEquals("是否接受一年供货的承诺", compiled.patch().decisionCheckpoint().prompt());
        assertTrue(compiled.patch().outcome().summary().contains("尚未签约"));
        assertTrue(compiled.patch().outcome().summary().contains("是否接受一年供货的承诺"));
    }

    @Test
    void successfulPrefixWithPendingOrBlockedWorkIsOnlyPartiallyComplete() {
        List<Step> steps = List.of(ordinary("询问价格", Resolution.SUCCEEDED, "获得报价及附加条件"),
                ordinary("完成交易", Resolution.NOT_EXECUTED, "新出现的供货义务尚未接受，未成交"));
        for (StopReason reason : List.of(StopReason.DECISION_REQUIRED, StopReason.BLOCKED)) {
            StoryTurnDecision draft = replaceProposal(decision(steps, false, false),
                    new Proposal(steps, new Stop(reason, "尚需处理一年供货的附加要求")));
            assertEquals(StoryPatch.OutcomeResolution.PARTIAL,
                    compiler.compileDetailed(base(), draft).patch().outcome().resolution());
        }
    }

    @Test
    void rejectsMissingDuplicateCultivationAndEffectsOnUnexecutedSteps() {
        assertThrows(ProtocolException.class, () -> compiler.compileDetailed(base(),
                decision(List.of(ordinary("吐纳", Resolution.SUCCEEDED, "已经吐纳")), true, false)));
        assertThrows(ProtocolException.class, () -> compiler.compileDetailed(base(),
                decision(List.of(cultivation(Resolution.SUCCEEDED), cultivation(Resolution.SUCCEEDED)), true, false)));
        Step skipped = new Step("买药", Resolution.NOT_EXECUTED, "未购买", false, List.of(0), List.of(), List.of());
        assertThrows(ProtocolException.class, () -> compiler.compileDetailed(base(),
                decision(List.of(skipped), false, false, dummyAssets(1), List.of(), null)));
        Step speaking = new Step("交谈", Resolution.NOT_EXECUTED, "没有交谈", false, List.of(), List.of(),
                List.of(new Statement(null, "掌柜", "五十四文")));
        assertThrows(ProtocolException.class, () -> compiler.compileDetailed(base(), decision(List.of(speaking), false, false)));
    }

    @Test
    void rejectsForwardDuplicateDependenciesAndUnassignedOrReorderedAssets() {
        Step first = ordinary("询问", Resolution.SUCCEEDED, "得到答复");
        for (List<Dependency> dependencies : List.of(List.of(new Dependency(1, Expected.SUCCEEDED)),
                List.of(new Dependency(0, Expected.SUCCEEDED), new Dependency(0, Expected.SUCCEEDED)))) {
            Step invalid = new Step("后续", Resolution.SUCCEEDED, "已经完成", false, List.of(), dependencies, List.of());
            assertThrows(ProtocolException.class, () -> compiler.compileDetailed(base(), decision(List.of(first, invalid), false, false)));
        }
        for (List<Integer> indexes : List.of(List.of(1, 0), List.of(0, 0), List.of(0))) {
            Step invalid = new Step("处理物品", Resolution.SUCCEEDED, "已处理", false, indexes, List.of(), List.of());
            ProtocolException error = assertThrows(ProtocolException.class, () -> compiler.compileDetailed(base(),
                    decision(List.of(invalid), false, false, dummyAssets(2), List.of(), null)));
            if (indexes.equals(List.of(0))) {
                assertEquals("ASSET_UNASSIGNED", error.code());
                assertTrue(error.getMessage().contains("索引 [1]"));
            }
        }
    }

    @Test
    void malformedCollectionsAreClassifiedAndBoundsAreEnforced() {
        StoryTurnDecision draft = decision(List.of(ordinary("交谈", Resolution.SUCCEEDED, "得到答复")), false, false);
        for (List<Step> steps : List.of(Arrays.asList((Step) null), List.<Step>of(),
                IntStream.range(0, 17).mapToObj(i -> ordinary("交谈", Resolution.SUCCEEDED, "得到答复")).toList())) {
            StoryTurnDecision malformed = replaceProposal(draft, new Proposal(steps, new Stop(StopReason.ACTION_RESOLVED, "")));
            assertThrows(ProtocolException.class, () -> compiler.compileDetailed(base(), malformed));
        }
        List<Statement> statements = IntStream.range(0, 9).mapToObj(i -> new Statement(null, "掌柜", "第" + i + "项报价")).toList();
        Step tooMany = new Step("询价", Resolution.SUCCEEDED, "得到报价", false, List.of(), List.of(), statements);
        assertThrows(ProtocolException.class, () -> compiler.compileDetailed(base(), decision(List.of(tooMany), false, false)));
    }

    @Test
    void sourceAliasBecomesFormalIdWithoutCreatingWorldFactOrIndependentMemoryDraft() {
        StoryTurnDecision.EntityIntent person = new StoryTurnDecision.EntityIntent("", "新结识的掌柜", "CHARACTER",
                "陆掌柜", "交谈后", "本轮结识的药庄主人", null, null, null, null, null, null, true);
        Step step = new Step("询问价钱", Resolution.SUCCEEDED, "获得这批草药的报价", false, List.of(), List.of(),
                List.of(new Statement(person.localRef(), "模型别名", "这批药当下收五十四文。")));
        StoryFactIntentCompiler.Compilation compiled = compiler.compileDetailed(base(),
                decision(List.of(step), false, false, emptyProcess(), List.of(person), null));
        StoryStateReducer.Applied applied = reducer.reduce(base(), compiled.patch());
        Accepted accepted = OrderedTurnResult.resolveRefs(compiled.acceptedResult(), applied.acceptedPatch().entities());
        Statement statement = accepted.steps().getFirst().statements().getFirst();
        assertEquals(applied.acceptedPatch().entities().getFirst().entityId(), statement.sourceRef());
        assertEquals("陆掌柜", statement.sourceLabel());
        assertEquals("这批药当下收五十四文。", statement.statement());
        assertDoesNotThrow(() -> OrderedTurnResult.validateAccepted(accepted));
        assertEquals(List.of("EVENT", "CLUE"), compiled.patch().memories().stream().map(StoryPatch.MemoryDraft::memoryType).toList());
        assertTrue(applied.projection().worldFacts().isEmpty());
    }

    @Test
    void storedValidationRejectsUnknownVersionBadIndexesAndUnresolvedSourcesWithoutRejudgingRules() {
        Accepted accepted = compiler.compileDetailed(base(), decision(List.of(
                ordinary("访友", Resolution.FAILED, "未能见面")), false, false)).acceptedResult();
        assertDoesNotThrow(() -> OrderedTurnResult.validateAccepted(accepted));
        assertThrows(IllegalStateException.class, () -> OrderedTurnResult.validateAccepted(new Accepted(99, accepted.steps(), accepted.stop())));
        AcceptedStep step = accepted.steps().getFirst();
        AcceptedStep badIndex = new AcceptedStep(2, step.action(), step.resolution(), step.result(), false, List.of(), List.of(), List.of());
        assertThrows(IllegalStateException.class, () -> OrderedTurnResult.validateAccepted(new Accepted(1, List.of(badIndex), accepted.stop())));
        AcceptedStep badSource = new AcceptedStep(0, "交谈", Resolution.SUCCEEDED, "听到说法", false, List.of(), List.of(),
                List.of(new Statement("contract-ref:new-1", "掌柜", "五十四文")));
        assertThrows(IllegalStateException.class, () -> OrderedTurnResult.validateAccepted(new Accepted(1, List.of(badSource), accepted.stop())));
    }

    @Test
    void teachingAndPracticeSurviveWithOrWithoutRealmGain() {
        String experience = "依周执事指点放松肩背，缓步时能调匀呼吸，快走仍会散乱";
        Statement teaching = new Statement(null, "周执事", "先放松肩背，缓步试行，不必强行引气");
        for (boolean gain : List.of(false, true)) {
            for (int marks : List.of(0, 1)) {
                StoryProjection before = projection(WorldPackage.RealmPhase.EARLY, marks, 2, Map.of());
                Step study = new Step("请教并当场练习", Resolution.SUCCEEDED, experience, gain,
                        List.of(), List.of(), List.of(teaching));
                var compiled = compiler.compileDetailed(before, decision(List.of(study), gain, false));
                Accepted accepted = compiled.acceptedResult();
                assertEquals(2, accepted.protocolVersion());
                assertEquals(experience, accepted.steps().getFirst().result());
                assertEquals(List.of(teaching), accepted.steps().getFirst().statements());
                assertTrue(accepted.memories().getFirst().summary().contains(experience));
                assertTrue(accepted.memories().get(1).summary().contains(teaching.statement()));
                if (gain) {
                    var realm = accepted.steps().getFirst().realmResult();
                    assertEquals(marks == 0 ? StoryPatch.CultivationResolution.PROGRESSED
                            : StoryPatch.CultivationResolution.PHASE_ADVANCED, realm.resolution());
                    assertEquals(compiled.patch().cultivation().progressNote(), realm.result());
                } else {
                    assertNull(accepted.steps().getFirst().realmResult());
                    assertNull(compiled.patch().cultivation());
                    assertEquals(before.protagonistCultivation(), reducer.reduce(before, compiled.patch()).projection().protagonistCultivation());
                }
                assertDoesNotThrow(() -> OrderedTurnResult.validateAccepted(accepted));
            }
        }
    }

    @Test
    void changedResultRejectsEvenWithoutDependenciesOrDecisionStop() {
        StoryTurnDecision draft = decision(List.of(cultivation(Resolution.FAILED)), false, true);
        ProtocolException rejected = assertThrows(ProtocolException.class, () -> compiler.compileDetailed(
                projection(WorldPackage.RealmPhase.PERFECT, 1, 2, Map.of()), draft));
        assertEquals("CULTIVATION_RESULT_CHANGED", rejected.code());
        assertTrue(rejected.getMessage().contains("SUCCEEDED"));
        assertTrue(rejected.getMessage().contains("完整候选"));
        assertEquals(Resolution.FAILED, draft.orderedResult().steps().getFirst().resolution());
    }

    @Test
    void missingBindingCannotSilentlyBecomeOrdinaryStep() {
        Step missing = new Step("请教", Resolution.SUCCEEDED, "听到指点", null, List.of(), List.of(), List.of());
        var rejected = assertThrows(ProtocolException.class, () -> compiler.compileDetailed(base(),
                decision(List.of(missing), false, false)));
        assertEquals("BINDING_REQUIRED", rejected.code());
    }

    @Test
    void bindingFeedbackPreservesOrdinaryPracticeInsteadOfDemandingGain() {
        Step study = new Step("请教并练习", Resolution.SUCCEEDED, "试练但没有明显改善", true,
                List.of(), List.of(), List.of(new Statement(null, "周执事", "先放松肩背")));
        var rejected = assertThrows(ProtocolException.class, () -> compiler.compileDetailed(base(),
                decision(List.of(study), false, false)));
        assertEquals("CULTIVATION_BINDING", rejected.code());
        assertTrue(rejected.getMessage().contains("progressGained=false"));
        assertTrue(rejected.getMessage().contains("普通练习和教师声明仍完整保留"));
    }

    @Test
    void storedV1IsNotReinterpretedAndV2RequiresSeparateRealmResult() {
        Stop stop = new Stop(StopReason.ACTION_RESOLVED, "本轮结束");
        AcceptedStep legacy = new AcceptedStep(0, "修行", Resolution.SUCCEEDED,
                "修行进展达到门槛，进入炼气中期。", true, List.of(), List.of(), List.of());
        Accepted old = new Accepted(1, List.of(legacy), stop);
        assertDoesNotThrow(() -> OrderedTurnResult.validateAccepted(old));
        assertNull(old.steps().getFirst().realmResult());
        assertEquals(legacy.result(), old.steps().getFirst().result());
        assertThrows(IllegalStateException.class, () -> OrderedTurnResult.validateAccepted(new Accepted(2, List.of(legacy), stop)));
        var realm = new OrderedTurnResult.RealmResult(StoryPatch.CultivationResolution.PROGRESSED, "得到进展");
        AcceptedStep newStep = new AcceptedStep(0, "试练", Resolution.SUCCEEDED, "呼吸稳定", true,
                List.of(), List.of(), List.of(new Statement(null, "师傅", "缓步试行")), realm);
        assertDoesNotThrow(() -> OrderedTurnResult.validateAccepted(new Accepted(2, List.of(newStep), stop)));
        assertThrows(IllegalStateException.class, () -> OrderedTurnResult.validateAccepted(new Accepted(1, List.of(newStep), stop)));
        AcceptedStep unbound = new AcceptedStep(0, "试练", Resolution.SUCCEEDED, "呼吸稳定", false,
                List.of(), List.of(), List.of(), realm);
        assertThrows(IllegalStateException.class, () -> OrderedTurnResult.validateAccepted(new Accepted(2, List.of(unbound), stop)));
    }

    private StoryTurnDecision decision(List<Step> steps, boolean progress, boolean breakthrough) {
        return decision(steps, progress, breakthrough, emptyProcess(), List.of(), null);
    }

    private StoryTurnDecision decision(List<Step> steps, boolean progress, boolean breakthrough,
                                       AssetProcess.Proposal process, List<StoryTurnDecision.EntityIntent> entities,
                                       StoryTurnDecision.CoreStateIntent core) {
        return new StoryTurnDecision(null, new SemanticDuration(SemanticDuration.Unit.PHASE, 1, ""),
                StoryPatch.TimeResolution.COMPLETE, "", core,
                new StoryTurnDecision.CultivationIntent(progress, breakthrough, "本轮进行了明确的修行尝试", List.of("PLAYER_ACTION")),
                entities, List.of(), List.of(), null, List.of(), process, null,
                new Proposal(steps, new Stop(StopReason.ACTION_RESOLVED, "")));
    }

    private StoryTurnDecision replaceProposal(StoryTurnDecision d, Proposal proposal) {
        return new StoryTurnDecision(d.outcome(), d.duration(), d.timeResolution(), d.interruptionReason(),
                d.coreState(), d.cultivation(), d.entities(), d.worldFacts(), d.memories(), d.decisionCheckpoint(),
                d.sceneBeats(), d.assetProcess(), d.actionUnderstanding(), proposal);
    }

    private Step cultivation(Resolution resolution) {
        return new Step("尝试修行或突破", resolution, "调息后气息逐渐稳定，仍需继续练习", true, List.of(), List.of(), List.of());
    }

    private Step ordinary(String action, Resolution resolution, String result) {
        return new Step(action, resolution, result, false, List.of(), List.of(), List.of());
    }

    private AssetProcess.Proposal emptyProcess() { return new AssetProcess.Proposal(1, List.of()); }
    private AssetProcess.Proposal dummyAssets(int count) {
        return new AssetProcess.Proposal(1, IntStream.range(0, count).mapToObj(i -> new AssetProcess.Step(
                "step-" + i, AssetProcess.Kind.INVENTORY, "仅用于验证结果归属先拒绝，不会执行资产编译",
                List.of(), List.of(), List.of())).toList());
    }

    private StoryProjection base() { return projection(WorldPackage.RealmPhase.EARLY, 0, 2, Map.of()); }

    private StoryProjection projection(WorldPackage.RealmPhase phase, int marks, int required,
                                       Map<String, StoryProjection.EntityState> entities) {
        List<String> names = List.of("引气", "炼气", "筑基", "金丹", "元婴", "化神");
        List<CultivationSystem.Stage> stages = IntStream.range(0, names.size()).mapToObj(i ->
                new CultivationSystem.Stage("stage-0" + (i + 1), i + 1, names.get(i), names.get(i) + "阶段",
                        List.of(), i == names.size() - 1 ? List.<String>of() : List.of("stage-0" + (i + 2)))).toList();
        CultivationSystem system = new CultivationSystem("ordered-cultivation", 1, "清气修行道", "合成修行标尺", "人族",
                "stage-01", stages, new CultivationSystem.Confirmation(CultivationSystem.ConfirmationSource.WORLD_PACKAGE_FIXED,
                Instant.parse("2026-09-07T00:00:00Z")));
        return new StoryProjection(StoryWorldTime.opening(), new StoryProjection.CoreState("药庄", "平静", "炼气", "问仙"),
                entities, Map.of(), Map.of(), List.of(), StoryTestFixtures.creationContract(), StoryTestFixtures.playPreference(),
                system, new ProtagonistCultivation(system.systemId(), "stage-02", phase, marks, required, "原有进展"));
    }
}

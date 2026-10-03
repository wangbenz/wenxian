package com.wenxian.game.story.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.reducer.StoryStateReducer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证语义事实意图能够稳定编译，而不要求导演模型了解数据库操作和正式实体 ID。 */
class StoryFactIntentCompilerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final StoryFactIntentCompiler compiler = new StoryFactIntentCompiler();
    private final AtomicInteger ids = new AtomicInteger();
    private final StoryStateReducer reducer = new StoryStateReducer(
            new StoryPatchValidator(), prefix -> prefix + "-directed-" + ids.incrementAndGet());

    @Test
    void compilesNewCarriedItemAndLetsServerAssignStableIdentity() {
        StoryTurnDecision.EntityIntent item = new StoryTurnDecision.EntityIntent(
                "", "medicine", "item", "清心丹", "刚刚得到", "装在青瓷瓶中",
                mapper.createObjectNode().put("quality", "普通"),
                new StoryPatch.QuantityValue(true, 2),
                new StoryTurnDecision.CustodyIntent(StoryPatch.HolderType.PLAYER, "", ""),
                null, null, null, true);

        StoryPatch compiled = compiler.compile(projection(Map.of()), decision(List.of(item), List.of()));
        StoryStateReducer.Applied applied = reducer.reduce(projection(Map.of()), compiled);
        StoryPatch.EntityPatch accepted = applied.acceptedPatch().entities().getFirst();

        assertEquals(StoryPatch.EntityOperation.CREATE, accepted.operation());
        assertTrue(accepted.entityId().startsWith("item-directed-"));
        assertEquals(StoryPatch.HolderType.PLAYER, accepted.custody().holderType());
        assertTrue(applied.projection().entities().get(accepted.entityId()).inPossession());
        assertEquals(2, applied.projection().entities().get(accepted.entityId()).quantity().value());
    }

    @Test
    void resolvesItemCustodyToCharacterCreatedInSameTurn() {
        StoryTurnDecision.EntityIntent keeper = new StoryTurnDecision.EntityIntent(
                "", "keeper", "CHARACTER", "柳青儿", "在客栈等候", "本轮首次确认的重要人物",
                null, null, null, null, null,
                new StoryPatch.RelationshipValue("旧识", "本轮相认"), true);
        StoryTurnDecision.EntityIntent item = new StoryTurnDecision.EntityIntent(
                "", "letter", "ITEM", "引荐信", "由柳青儿保管", "尚未交给主角",
                null, new StoryPatch.QuantityValue(true, 1),
                new StoryTurnDecision.CustodyIntent(StoryPatch.HolderType.CHARACTER, "", "keeper"),
                null, null, null, true);

        StoryStateReducer.Applied applied = reducer.reduce(projection(Map.of()),
                compiler.compile(projection(Map.of()), decision(List.of(keeper, item), List.of())));
        String keeperId = applied.projection().entities().values().stream()
                .filter(value -> "CHARACTER".equals(value.entityType())).findFirst().orElseThrow().id();
        StoryProjection.EntityState letter = applied.projection().entities().values().stream()
                .filter(value -> "ITEM".equals(value.entityType())).findFirst().orElseThrow();

        assertEquals(StoryPatch.LifecycleState.ACTIVE,
                applied.projection().entities().get(keeperId).lifecycle());
        assertEquals(keeperId, letter.custody().holderRef());
        assertFalse(letter.inPossession());
    }

    @Test
    void resolvesUnicodeModelAliasThroughServerOwnedReference() {
        StoryTurnDecision.EntityIntent keeper = new StoryTurnDecision.EntityIntent(
                "", "负责装货的伙计", "CHARACTER", "阿禾", "仍在集市", "本轮确认的商队伙计",
                null, null, null, null, null,
                new StoryPatch.RelationshipValue("初识", "接受盘问"), true);
        StoryTurnDecision.EntityIntent item = new StoryTurnDecision.EntityIntent(
                "", "带血妖骨", "ITEM", "带血妖骨", "由阿禾看守", "尚未交给主角",
                null, new StoryPatch.QuantityValue(true, 1),
                new StoryTurnDecision.CustodyIntent(StoryPatch.HolderType.CHARACTER, "", "负责装货的伙计"),
                null, null, null, true);

        StoryStateReducer.Applied applied = reducer.reduce(projection(Map.of()),
                compiler.compile(projection(Map.of()), decision(List.of(keeper, item), List.of())));
        String keeperId = applied.projection().entities().values().stream()
                .filter(value -> "CHARACTER".equals(value.entityType())).findFirst().orElseThrow().id();
        StoryProjection.EntityState bone = applied.projection().entities().values().stream()
                .filter(value -> "ITEM".equals(value.entityType())).findFirst().orElseThrow();

        assertEquals(keeperId, bone.custody().holderRef());
        assertFalse(bone.inPossession());
    }

    @Test
    void updatesExistingItemWithoutLettingModelRepeatItsTypeOrIdentity() {
        StoryProjection.EntityState pill = new StoryProjection.EntityState(
                "item-pill", "ITEM", "凝气丹", "尚余三枚", "随身丹药", null, true,
                new StoryPatch.QuantityValue(true, 3), true,
                new StoryPatch.CustodyValue(StoryPatch.HolderType.PLAYER, null), null, null);
        StoryProjection before = projection(Map.of(pill.id(), pill));
        StoryTurnDecision.EntityIntent consume = new StoryTurnDecision.EntityIntent(
                pill.id(), "", "", "", "尚余两枚", "已经服用一枚", null,
                new StoryPatch.QuantityValue(true, 2),
                new StoryTurnDecision.CustodyIntent(StoryPatch.HolderType.PLAYER, "", ""),
                null, null, null, true);

        StoryStateReducer.Applied applied = reducer.reduce(before,
                compiler.compile(before, decision(List.of(consume), List.of())));

        assertEquals(StoryPatch.EntityOperation.UPDATE,
                applied.acceptedPatch().entities().getFirst().operation());
        assertEquals(2, applied.projection().entities().get(pill.id()).quantity().value());
    }

    @Test
    void rejectsUnknownStableIdentityButDropsMalformedSoftMemory() {
        StoryTurnDecision.EntityIntent unknown = new StoryTurnDecision.EntityIntent(
                "item-missing", "", "ITEM", "", "", "", null, null,
                null, null, null, null, true);
        assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(projection(Map.of()), decision(List.of(unknown), List.of())));

        StoryPatch.MemoryDraft incomplete = new StoryPatch.MemoryDraft(
                "EVENT", "", "只有摘要但没有标题", "NORMAL", List.of());
        StoryPatch compiled = compiler.compile(projection(Map.of()), decision(List.of(), List.of(incomplete)));
        assertTrue(compiled.memories().isEmpty(), "辅助记忆缺项不应阻断核心回合");
    }

    @Test
    void treatsInventedWorldFactIdAsNewFactInsteadOfBlockingTheTurn() {
        StoryTurnDecision.WorldFactIntent discovery = new StoryTurnDecision.WorldFactIntent(
                "gate_has_ancient_inscriptions", "石门背面存在古老铭文", "本轮清理苔痕后亲眼确认");
        StoryTurnDecision decision = new StoryTurnDecision(
                new StoryPatch.TurnOutcome(StoryPatch.OutcomeResolution.SUCCEEDED, "发现了石门铭文"),
                new SemanticDuration(SemanticDuration.Unit.PHASE, 1, ""),
                StoryPatch.TimeResolution.COMPLETE, "", null, List.of(), List.of(discovery), List.of(),
                new StoryPatch.DecisionCheckpoint(false, null), List.of("清理苔痕", "铭文显露"));

        StoryPatch compiled = compiler.compile(projection(Map.of()), decision);
        StoryPatch.WorldFactPatch fact = compiled.worldFacts().getFirst();
        StoryStateReducer.Applied applied = reducer.reduce(projection(Map.of()), compiled);

        assertEquals(StoryPatch.WorldFactOperation.EXPAND, fact.operation());
        assertEquals(null, fact.factId());
        assertEquals("石门背面存在古老铭文",
                applied.projection().worldFacts().values().iterator().next().statement());
    }

    @Test
    void acceptsOneProgressMarkAndIgnoresFreeTextRealm() {
        StoryProjection before = cultivationProjection();
        StoryTurnDecision decision = new StoryTurnDecision(
                new StoryPatch.TurnOutcome(StoryPatch.OutcomeResolution.SUCCEEDED, "吐纳有所精进"),
                new SemanticDuration(SemanticDuration.Unit.MONTH, 3, ""),
                StoryPatch.TimeResolution.COMPLETE, "",
                new StoryTurnDecision.CoreStateIntent(null, "气息稳固", "元婴"),
                new StoryTurnDecision.CultivationIntent(true, false, "完成了一轮有效吐纳",
                        List.of("model-invented-evidence")),
                List.of(), List.of(), List.of(),
                new StoryPatch.DecisionCheckpoint(false, null), List.of("闭关", "吐纳", "出关"));

        StoryPatch compiled = compiler.compile(before, decision);
        StoryStateReducer.Applied applied = reducer.reduce(before, compiled);

        assertEquals(null, compiled.coreState().realm(), "自由文本境界不能覆盖已确认体系");
        assertEquals(StoryPatch.CultivationResolution.PROGRESSED, compiled.cultivation().resolution());
        assertEquals(List.of("PLAYER_ACTION"), compiled.cultivation().evidenceRefs(),
                "伪造依据引用应降级到本轮玩家行动，而不是阻断剧情");
        assertEquals("stage-02", applied.projection().protagonistCultivation().currentStageId());
        assertEquals(1, applied.projection().protagonistCultivation().progressMarks());
        assertEquals("炼气初期", applied.projection().coreState().realm());
        assertEquals("完成了一轮有效吐纳", applied.projection().protagonistCultivation().progressNote());
    }

    @Test
    void rejectsMalformedCultivationIntentWithoutBlockingThePlayableTurn() {
        StoryProjection before = cultivationProjection();
        StoryTurnDecision decision = cultivationDecision(StoryPatch.OutcomeResolution.SUCCEEDED,
                true, true, "");

        StoryPatch compiled = compiler.compile(before, decision);
        StoryStateReducer.Applied applied = reducer.reduce(before, compiled);

        assertEquals(StoryPatch.CultivationResolution.REJECTED, compiled.cultivation().resolution());
        assertEquals("stage-02", compiled.cultivation().targetStageId());
        assertEquals("stage-02", applied.projection().protagonistCultivation().currentStageId());
        assertEquals(0, applied.projection().protagonistCultivation().progressMarks());
        assertEquals("炼气初期", applied.projection().coreState().realm());
    }

    @Test
    void partialOutcomeCanStillCommitOneConcreteProgressMark() {
        StoryProjection before = cultivationProjection();
        StoryPatch compiled = compiler.compile(before, cultivationDecision(
                StoryPatch.OutcomeResolution.PARTIAL, true, false, "实战中形成了一次领悟"));
        StoryStateReducer.Applied applied = reducer.reduce(before, compiled);

        assertEquals(StoryPatch.CultivationResolution.PROGRESSED, compiled.cultivation().resolution());
        assertEquals("stage-02", applied.projection().protagonistCultivation().currentStageId());
        assertEquals(1, applied.projection().protagonistCultivation().progressMarks());
        assertEquals("实战中形成了一次领悟", applied.projection().protagonistCultivation().progressNote());
    }

    @Test
    void advancesAtMostOnePhaseWhenProgressReachesCurrentThreshold() {
        StoryProjection before = withCultivation(cultivationProjection(),
                new ProtagonistCultivation("cultivation-test", "stage-02",
                        WorldPackage.RealmPhase.EARLY, 1, 2, "已有一枚进展"));

        StoryStateReducer.Applied applied = reducer.reduce(before, compiler.compile(before,
                cultivationDecision(StoryPatch.OutcomeResolution.SUCCEEDED, true, false,
                        "本轮吐纳达到初期门槛")));

        assertEquals(WorldPackage.RealmPhase.MIDDLE,
                applied.projection().protagonistCultivation().phase());
        assertEquals(0, applied.projection().protagonistCultivation().progressMarks());
        assertEquals(2, applied.projection().protagonistCultivation().requiredProgressMarks());
        assertEquals("炼气中期", applied.projection().coreState().realm());
    }

    @Test
    void preferenceChangeOnlyAffectsTheNextPhaseThreshold() {
        StoryProjection base = cultivationProjection();
        PlayPreference faster = new PlayPreference(base.playPreference().focus(),
                base.playPreference().combatFrequency(), PlayPreference.Pace.FAST,
                base.playPreference().guidance(), "", base.playPreference().updatedAt().plusSeconds(60));
        StoryProjection before = new StoryProjection(base.worldTime(), base.coreState(), base.entities(),
                base.worldFacts(), base.knowledge(), base.memories(), base.creationContract(), faster,
                base.cultivationSystem(), new ProtagonistCultivation("cultivation-test", "stage-02",
                WorldPackage.RealmPhase.EARLY, 1, 2, "进入初期时门槛仍为二"));

        StoryStateReducer.Applied applied = reducer.reduce(before, compiler.compile(before,
                cultivationDecision(StoryPatch.OutcomeResolution.SUCCEEDED, true, false,
                        "达到进入初期时固定的门槛")));

        assertEquals(WorldPackage.RealmPhase.MIDDLE,
                applied.projection().protagonistCultivation().phase());
        assertEquals(1, applied.projection().protagonistCultivation().requiredProgressMarks(),
                "FAST 只应用于新进入的中期");
    }

    @Test
    void serviceGuaranteesQualifiedExplicitBreakthroughDespiteRandomDirectorFailure() {
        StoryProjection before = withCultivation(cultivationProjection(),
                new ProtagonistCultivation("cultivation-test", "stage-02",
                        WorldPackage.RealmPhase.PERFECT, 1, 2, "炼气圆满"));

        StoryStateReducer.Applied applied = reducer.reduce(before, compiler.compile(before,
                cultivationDecision(StoryPatch.OutcomeResolution.FAILED, false, true,
                        "导演原本随机判断突破失败")));

        assertEquals(StoryPatch.OutcomeResolution.SUCCEEDED,
                applied.acceptedPatch().outcome().resolution());
        assertEquals("stage-03", applied.projection().protagonistCultivation().currentStageId());
        assertEquals(WorldPackage.RealmPhase.EARLY, applied.projection().protagonistCultivation().phase());
        assertEquals(0, applied.projection().protagonistCultivation().progressMarks());
        assertEquals("筑基初期", applied.projection().coreState().realm());
    }

    private StoryTurnDecision decision(List<StoryTurnDecision.EntityIntent> entities,
                                       List<StoryPatch.MemoryDraft> memories) {
        return new StoryTurnDecision(
                new StoryPatch.TurnOutcome(StoryPatch.OutcomeResolution.SUCCEEDED, "行动已经完成"),
                new SemanticDuration(SemanticDuration.Unit.PHASE, 1, ""),
                StoryPatch.TimeResolution.COMPLETE, "", null, entities, List.of(), memories,
                new StoryPatch.DecisionCheckpoint(false, null), List.of("行动发生", "在结果处停下"));
    }

    private StoryProjection projection(Map<String, StoryProjection.EntityState> entities) {
        return new StoryProjection(StoryWorldTime.opening(),
                new StoryProjection.CoreState("石门前", "平静", "凡俗", "问仙"),
                entities, Map.of(), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(), null, null);
    }

    private StoryTurnDecision cultivationDecision(StoryPatch.OutcomeResolution resolution,
                                                   boolean progressGained,
                                                   boolean breakthroughAttempted,
                                                   String reason) {
        return new StoryTurnDecision(
                new StoryPatch.TurnOutcome(resolution, reason.isBlank() ? "本轮行动仍有可玩结果" : reason),
                new SemanticDuration(SemanticDuration.Unit.MONTH, 1, ""),
                StoryPatch.TimeResolution.COMPLETE, "", null,
                new StoryTurnDecision.CultivationIntent(progressGained, breakthroughAttempted,
                        reason, List.of("PLAYER_ACTION")),
                List.of(), List.of(), List.of(), new StoryPatch.DecisionCheckpoint(false, null),
                List.of("尝试突破", "结果落定"));
    }

    private StoryProjection withCultivation(StoryProjection before, ProtagonistCultivation cultivation) {
        return new StoryProjection(before.worldTime(), before.coreState(), before.entities(), before.worldFacts(),
                before.knowledge(), before.memories(), before.creationContract(), before.playPreference(),
                before.cultivationSystem(), cultivation);
    }

    private StoryProjection cultivationProjection() {
        CultivationSystem system = new CultivationSystem("cultivation-test", 1,
                "青冥炼真道", "借天地清气磨炼真形。", "人族", "stage-01", List.of(
                stage("stage-01", 1, "引气", "stage-02"),
                stage("stage-02", 2, "炼气", "stage-03"),
                stage("stage-03", 3, "筑基", "stage-04"),
                stage("stage-04", 4, "金丹", "stage-05"),
                stage("stage-05", 5, "元婴", "stage-06"),
                stage("stage-06", 6, "化神", null)
        ), new CultivationSystem.Confirmation(
                CultivationSystem.ConfirmationSource.PLAYER_CONFIRMED_RECOMMENDATION,
                Instant.parse("2026-08-20T12:00:00Z")));
        return new StoryProjection(StoryWorldTime.opening(),
                new StoryProjection.CoreState("石门前", "平静", "炼气", "问仙"),
                Map.of(), Map.of(), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(),
                system, new ProtagonistCultivation(system.systemId(), "stage-02", "气息渐稳"));
    }

    private CultivationSystem.Stage stage(String id, int order, String name, String nextId) {
        return new CultivationSystem.Stage(id, order, name, name + "阶段。", List.of(),
                nextId == null ? List.of() : List.of(nextId));
    }
}

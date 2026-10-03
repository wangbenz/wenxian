package com.wenxian.game.story.reducer;

import com.wenxian.game.story.domain.CultivationSystem;
import com.wenxian.game.story.domain.ProtagonistCultivation;
import com.wenxian.game.story.domain.StoryCorrectionProposal;
import com.wenxian.game.story.domain.StoryCorrectionProposal.CorrectionKind;
import com.wenxian.game.story.domain.StoryCorrectionValidator;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.KnowledgePatch;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.WorldFactOperation;
import com.wenxian.game.story.domain.StoryPatch.WorldFactPatch;
import com.wenxian.game.story.domain.StoryPatchValidator;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.domain.StoryProjection.KnowledgeState;
import com.wenxian.game.story.domain.StoryProjection.WorldFactState;
import com.wenxian.game.story.domain.StoryWorldTime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryCorrectionReducerTest {
    private final StoryCorrectionValidator validator = new StoryCorrectionValidator();
    private final StoryCorrectionReducer reducer = new StoryCorrectionReducer(validator,
            new StoryStateReducer(new StoryPatchValidator(), prefix -> prefix + "-generated"));

    @Test
    void previewsButDoesNotApplyFactCorrectionWithoutPlayerConfirmation() {
        StoryCorrectionProposal proposal = factProposal(new StoryPatch.CoreStatePatch(
                "青石镇北门", null, null, null), List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> reducer.apply(projection(), proposal, false));

        assertEquals("修正提案尚未获得玩家确认", error.getMessage());
    }

    @Test
    void appliesConfirmedFactCorrectionWithoutAdvancingTimeOrTurnNarrative() {
        EntityPatch consume = new EntityPatch(EntityOperation.UPDATE, "item-pill", "ITEM", null,
                "已经用去一枚", null, null, new QuantityValue(true, 2), true,
                null, null);
        StoryCorrectionProposal proposal = factProposal(null, List.of(consume));

        StoryCorrectionReducer.Applied applied = reducer.apply(projection(), proposal, true);

        assertEquals(StoryWorldTime.opening(), applied.projection().worldTime());
        assertEquals(2, applied.projection().entities().get("item-pill").quantity().value());
        assertEquals("item-pill", applied.acceptedProposal().entities().getFirst().entityId());
    }

    @Test
    void keepsCognitionCorrectionSeparateFromObjectiveWorldFact() {
        StoryCorrectionProposal proposal = new StoryCorrectionProposal(CorrectionKind.COGNITION,
                "师尊只是相信天门断绝，并非客观结论。", "确认只修正师尊的认知？", null,
                List.of(), List.of(), List.of(new KnowledgePatch("character-mentor", "world-fact-ascension",
                "师尊相信天门已经断绝", List.of("turn-8"))));

        StoryProjection after = reducer.apply(projection(), proposal, true).projection();

        assertEquals("世人尚不能证明天门是否存在",
                after.worldFacts().get("world-fact-ascension").statement());
        assertTrue(after.knowledge().values().stream()
                .map(KnowledgeState::summary)
                .anyMatch("师尊相信天门已经断绝"::equals));
    }

    @Test
    void rejectsMixedCorrectionThatTriesToChangeFactAndCognitionTogether() {
        StoryCorrectionProposal proposal = new StoryCorrectionProposal(CorrectionKind.FACT,
                "同时改地点和师尊认知。", "确认这项修正？",
                new StoryPatch.CoreStatePatch("北荒", null, null, null), List.of(), List.of(),
                List.of(new KnowledgePatch("character-mentor", "world-fact-ascension",
                        "师尊知道天门仍在", List.of("turn-12"))));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(proposal));

        assertEquals("事实修正不能同时改写人物认知", error.getMessage());
    }

    @Test
    void worldRevisionRequiresWorldChannelAndRejectsNarrativeTransformation() {
        StoryCorrectionProposal transformation = worldProposal(new WorldFactPatch(
                WorldFactOperation.TRANSFORM, "world-fact-ascension", "天门在雷劫中崩裂", "剧情事件"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(transformation));
        assertEquals("剧情内自然转变不能通过世界修订入口提交", error.getMessage());

        StoryCorrectionProposal revision = worldProposal(new WorldFactPatch(
                WorldFactOperation.REVISE, "world-fact-ascension", "天门仍在，只是被阵法遮蔽",
                "玩家明确替换原设定"));
        StoryProjection after = reducer.apply(projection(), revision, true).projection();

        assertEquals("天门仍在，只是被阵法遮蔽",
                after.worldFacts().get("world-fact-ascension").statement());
        assertEquals(2, after.worldFacts().get("world-fact-ascension").revision());
    }

    @Test
    void rejectsConfirmedCorrectionThatMakesNoActualChange() {
        StoryCorrectionProposal proposal = factProposal(new StoryPatch.CoreStatePatch(
                "青石镇", null, null, null), List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> reducer.apply(projection(), proposal, true));

        assertEquals("修正提案没有产生实际事实变化", error.getMessage());
    }

    @Test
    void correctsStructuredCultivationToAnyKnownStageWithoutAdvancingTime() {
        StoryProjection before = cultivationProjection();
        StoryCorrectionProposal proposal = new StoryCorrectionProposal(CorrectionKind.FACT,
                "当前阶段记录错误，应为金丹。", "确认修正为金丹？",
                new StoryPatch.CoreStatePatch(null, null, "金丹", null),
                new StoryPatch.CultivationPatch("stage-04", "金丹已成",
                        StoryPatch.CultivationResolution.CORRECTED, "玩家明确纠正命卷事实"),
                List.of(), List.of(), List.of());

        StoryCorrectionReducer.Applied applied = reducer.apply(before, proposal, true);

        assertEquals(before.worldTime(), applied.projection().worldTime());
        assertEquals("stage-04", applied.projection().protagonistCultivation().currentStageId());
        assertEquals("金丹初期", applied.projection().coreState().realm());
        assertNull(applied.acceptedProposal().coreState().realm());
        assertEquals(StoryPatch.CultivationResolution.CORRECTED,
                applied.acceptedProposal().cultivation().resolution());
    }

    @Test
    void rejectsFreeTextRealmCorrectionForStructuredCultivationSave() {
        StoryCorrectionProposal proposal = factProposal(new StoryPatch.CoreStatePatch(
                null, null, "元婴", null), List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> reducer.apply(cultivationProjection(), proposal, true));

        assertEquals("当前命卷的境界只能通过已确认主修体系修正", error.getMessage());
    }

    private StoryCorrectionProposal factProposal(StoryPatch.CoreStatePatch core, List<EntityPatch> entities) {
        return new StoryCorrectionProposal(CorrectionKind.FACT, "纠正已经保存错误的客观事实。",
                "确认按以上内容修正命卷？", core, entities, List.of(), List.of());
    }

    private StoryCorrectionProposal worldProposal(WorldFactPatch patch) {
        return new StoryCorrectionProposal(CorrectionKind.WORLD, "玩家明确调整当前世界设定。",
                "确认让这条世界设定从此生效？", null, List.of(), List.of(patch), List.of());
    }

    private StoryProjection projection() {
        EntityState item = new EntityState("item-pill", "ITEM", "凝气丹", "尚余三枚", null,
                null, true, new QuantityValue(true, 3), true, null, null);
        EntityState mentor = new EntityState("character-mentor", "CHARACTER", "旧日师尊", "已经身故", null,
                null, true, null, null, null, LifecycleState.DEAD);
        WorldFactState fact = new WorldFactState("world-fact-ascension", "世人尚不能证明天门是否存在",
                1, true, WorldFactOperation.EXPAND, "世界起点");
        return new StoryProjection(StoryWorldTime.opening(), new CoreState("青石镇", "安稳", "引气初境", "追寻仙路"),
                Map.of(item.id(), item, mentor.id(), mentor), Map.of(fact.id(), fact), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(), null, null);
    }

    private StoryProjection cultivationProjection() {
        CultivationSystem system = new CultivationSystem("cultivation-test", 1, "青冥炼真道",
                "借天地清气磨炼真形。", "人族", "stage-01", List.of(
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
                new CoreState("青岐城", "平静", "炼气", "问道"),
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

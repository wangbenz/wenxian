package com.wenxian.game.story.reducer;

import com.wenxian.game.story.domain.CultivationSystem;
import com.wenxian.game.story.domain.ProtagonistCultivation;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.domain.WorldPackage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoryStateReducerCultivationTest {
    @Test
    void ordinaryTurnPreservesConfirmedCultivationContract() {
        CultivationSystem system = system();
        ProtagonistCultivation cultivation = new ProtagonistCultivation(system.systemId(), "stage-02", "气息渐稳");
        StoryProjection before = new StoryProjection(StoryWorldTime.opening(),
                new StoryProjection.CoreState("青岐城", "平静", "炼气", "问道"),
                Map.of(), Map.of(), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(),
                system, cultivation);
        StoryPatch patch = new StoryPatch("城中小憩", "你在客栈中调息片刻。",
                new StoryPatch.TimePassage(new SemanticDuration(SemanticDuration.Unit.SCENE, 1, ""),
                        StoryPatch.TimeResolution.COMPLETE, null),
                null, List.of(), List.of(), List.of(), List.of(),
                new StoryPatch.DecisionCheckpoint(false, null), List.of(), "");

        StoryProjection after = new StoryStateReducer().reduce(before, patch).projection();

        assertEquals(system, after.cultivationSystem());
        assertEquals(cultivation, after.protagonistCultivation());
    }

    @Test
    void acceptedPhaseAdvanceUpdatesPhaseAndDisplayRealmTogether() {
        CultivationSystem system = system();
        StoryProjection before = projection(system, new ProtagonistCultivation(system.systemId(), "stage-02",
                WorldPackage.RealmPhase.EARLY, 1, 2, "已有一枚进展"));
        StoryPatch patch = patch(new StoryPatch.CultivationPatch("stage-02", WorldPackage.RealmPhase.MIDDLE,
                0, 2, false, "炼气中期", List.of("PLAYER_ACTION"),
                StoryPatch.CultivationResolution.PHASE_ADVANCED, "进度达到当前门槛"),
                StoryPatch.OutcomeResolution.SUCCEEDED);

        StoryProjection after = new StoryStateReducer().reduce(before, patch).projection();

        assertEquals("stage-02", after.protagonistCultivation().currentStageId());
        assertEquals(WorldPackage.RealmPhase.MIDDLE, after.protagonistCultivation().phase());
        assertEquals(0, after.protagonistCultivation().progressMarks());
        assertEquals("炼气中期", after.coreState().realm());
    }

    @Test
    void reducerStillRejectsForgedCrossRankAdvance() {
        CultivationSystem system = system();
        StoryProjection before = projection(system, new ProtagonistCultivation(system.systemId(), "stage-02",
                WorldPackage.RealmPhase.PERFECT, 1, 2, "炼气圆满"));
        StoryPatch patch = patch(new StoryPatch.CultivationPatch("stage-05", WorldPackage.RealmPhase.EARLY,
                0, 2, true, "直接元婴", List.of("PLAYER_ACTION"),
                StoryPatch.CultivationResolution.RANK_ADVANCED, "伪造跨阶"),
                StoryPatch.OutcomeResolution.SUCCEEDED);

        assertThrows(IllegalArgumentException.class,
                () -> new StoryStateReducer().reduce(before, patch));
    }

    private StoryProjection projection(CultivationSystem system) {
        return projection(system, new ProtagonistCultivation(system.systemId(), "stage-02", "气息渐稳"));
    }

    private StoryProjection projection(CultivationSystem system, ProtagonistCultivation cultivation) {
        return new StoryProjection(StoryWorldTime.opening(),
                new StoryProjection.CoreState("青岐城", "平静", "炼气", "问道"),
                Map.of(), Map.of(), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(),
                system, cultivation);
    }

    private StoryPatch patch(StoryPatch.CultivationPatch cultivation,
                             StoryPatch.OutcomeResolution resolution) {
        return new StoryPatch("尝试突破", "你完成了一次闭关尝试。",
                new StoryPatch.TimePassage(new SemanticDuration(SemanticDuration.Unit.MONTH, 1, ""),
                        StoryPatch.TimeResolution.COMPLETE, null),
                null, List.of(), List.of(), List.of(), List.of(),
                new StoryPatch.DecisionCheckpoint(false, null), List.of(), "",
                new StoryPatch.TurnOutcome(resolution, "突破结果已落定"), cultivation);
    }

    private CultivationSystem system() {
        return new CultivationSystem("cultivation-test", 1, "青冥炼真道", "借天地清气磨炼真形。", "人族",
                "stage-01", List.of(
                stage("stage-01", 1, "引气", "stage-02"),
                stage("stage-02", 2, "炼气", "stage-03"),
                stage("stage-03", 3, "筑基", "stage-04"),
                stage("stage-04", 4, "金丹", "stage-05"),
                stage("stage-05", 5, "元婴", "stage-06"),
                stage("stage-06", 6, "化神", null)
        ), new CultivationSystem.Confirmation(CultivationSystem.ConfirmationSource.PLAYER_CONFIRMED_RECOMMENDATION,
                Instant.parse("2026-08-20T12:00:00Z")));
    }

    private CultivationSystem.Stage stage(String id, int order, String name, String nextId) {
        return new CultivationSystem.Stage(id, order, name, name + "阶段。", List.of(),
                nextId == null ? List.of() : List.of(nextId));
    }
}

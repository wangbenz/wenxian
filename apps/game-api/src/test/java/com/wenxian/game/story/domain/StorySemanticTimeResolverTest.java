package com.wenxian.game.story.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorySemanticTimeResolverTest {
    private final StorySemanticTimeResolver resolver = new StorySemanticTimeResolver();

    @Test
    void completeActionUsesPlayerAuthorizedSemanticDuration() {
        SemanticDuration planned = new SemanticDuration(SemanticDuration.Unit.YEAR, 3, "");
        StoryPatch proposed = patch(new StoryPatch.TimePassage(
                new SemanticDuration(SemanticDuration.Unit.YEAR, 3, ""),
                StoryPatch.TimeResolution.COMPLETE, null));

        StoryPatch resolved = resolver.resolve(projection(), proposed, planned);

        assertEquals(planned, resolved.time().duration());
    }

    @Test
    void interruptedActionKeepsCoarseActualDurationAndReason() {
        StoryPatch proposed = patch(new StoryPatch.TimePassage(
                new SemanticDuration(SemanticDuration.Unit.MONTH, 2, ""),
                StoryPatch.TimeResolution.INTERRUPTED, "洞府灵脉中断"));

        StoryPatch resolved = resolver.resolve(projection(), proposed,
                new SemanticDuration(SemanticDuration.Unit.YEAR, 3, ""));

        assertEquals(SemanticDuration.Unit.MONTH, resolved.time().duration().unit());
        assertEquals(2, resolved.time().duration().value());
        assertEquals("洞府灵脉中断", resolved.time().interruptionReason());
    }

    @Test
    void interruptedActionWithoutActualDurationDoesNotConsumeTheWholePlan() {
        StoryPatch proposed = patch(new StoryPatch.TimePassage(null,
                StoryPatch.TimeResolution.INTERRUPTED, "闭关刚开始便被叩门声打断"));

        StoryPatch resolved = resolver.resolve(projection(), proposed,
                new SemanticDuration(SemanticDuration.Unit.YEAR, 3, ""));

        assertEquals(new SemanticDuration(SemanticDuration.Unit.SCENE, 1, ""),
                resolved.time().duration());
    }

    @Test
    void proposalWithoutDurationDefaultsToOneScene() {
        StoryPatch resolved = resolver.resolve(projection(),
                patch(new StoryPatch.TimePassage(null,
                        StoryPatch.TimeResolution.COMPLETE, null)), null);

        assertEquals(SemanticDuration.Unit.SCENE, resolved.time().duration().unit());
        assertEquals(1, resolved.time().duration().value());
    }

    private StoryProjection projection() {
        return new StoryProjection(StoryWorldTime.opening(),
                new StoryProjection.CoreState("洞府", "平静", "引气", "问道"),
                Map.of(), Map.of(), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(), null, null);
    }

    private StoryPatch patch(StoryPatch.TimePassage time) {
        return new StoryPatch("闭关", "你在洞府中闭关。", time,
                new StoryPatch.CoreStatePatch(null, null, null, null),
                List.of(), List.of(), List.of(), List.of(),
                new StoryPatch.DecisionCheckpoint(false, null), List.of(), "",
                new StoryPatch.TurnOutcome(StoryPatch.OutcomeResolution.SUCCEEDED, "闭关完成"));
    }
}

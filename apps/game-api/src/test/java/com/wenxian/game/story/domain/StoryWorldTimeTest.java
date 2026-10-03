package com.wenxian.game.story.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoryWorldTimeTest {

    @Test
    void sceneKeepsCurrentPhaseWhilePhaseCrossesDayBoundary() {
        StoryWorldTime lateNight = new StoryWorldTime(3, 28, StoryWorldTime.Phase.LATE_NIGHT);

        assertEquals(lateNight, lateNight.advance(duration(SemanticDuration.Unit.SCENE, 1, "")));
        assertEquals(new StoryWorldTime(3, 29, StoryWorldTime.Phase.DAWN),
                lateNight.advance(duration(SemanticDuration.Unit.PHASE, 1, "")));
    }

    @Test
    void targetPhaseMovesToNextOccurrenceInsteadOfInventingMinutes() {
        StoryWorldTime night = new StoryWorldTime(2, 100, StoryWorldTime.Phase.NIGHT);

        assertEquals(new StoryWorldTime(2, 101, StoryWorldTime.Phase.DAWN),
                night.advance(duration(SemanticDuration.Unit.PHASE, 1, "清晨")));
    }

    @Test
    void commonModelPhaseAliasesResolveToTheCanonicalWorldClock() {
        StoryWorldTime afternoon = new StoryWorldTime(2, 100, StoryWorldTime.Phase.AFTERNOON);

        assertEquals(new StoryWorldTime(2, 100, StoryWorldTime.Phase.DUSK),
                afternoon.advance(duration(SemanticDuration.Unit.PHASE, 1, "EVENING")));
        assertEquals(StoryWorldTime.Phase.NOON, StoryWorldTime.Phase.parse("midday"));
    }

    @Test
    void monthAndYearAdvanceByGameCalendarSemantics() {
        StoryWorldTime start = new StoryWorldTime(2, 350, StoryWorldTime.Phase.AFTERNOON);

        assertEquals(new StoryWorldTime(3, 20, StoryWorldTime.Phase.AFTERNOON),
                start.advance(duration(SemanticDuration.Unit.MONTH, 1, "")));
        assertEquals(new StoryWorldTime(5, 350, StoryWorldTime.Phase.AFTERNOON),
                start.advance(duration(SemanticDuration.Unit.YEAR, 3, "")));
    }

    @Test
    void databaseOrderRoundTripsWithoutBecomingGameplayTime() {
        StoryWorldTime expected = new StoryWorldTime(9, 81, StoryWorldTime.Phase.DUSK);

        assertEquals(expected, StoryWorldTime.fromOrder(expected.toOrder()));
    }

    private SemanticDuration duration(SemanticDuration.Unit unit, int value, String targetPhase) {
        return new SemanticDuration(unit, value, targetPhase);
    }
}

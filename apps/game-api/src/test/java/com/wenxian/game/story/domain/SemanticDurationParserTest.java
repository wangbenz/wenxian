package com.wenxian.game.story.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticDurationParserTest {

    @Test
    void preservesMonthAndYearAsSemanticScale() {
        SemanticDuration threeMonths = SemanticDurationParser.parse("闭关三个月后出关").orElseThrow();
        SemanticDuration threeYears = SemanticDurationParser.parse("闭关三年尝试突破").orElseThrow();

        assertEquals(SemanticDuration.Unit.MONTH, threeMonths.unit());
        assertEquals(3, threeMonths.value());
        assertEquals("3个月", threeMonths.display());
        assertEquals(SemanticDuration.Unit.YEAR, threeYears.unit());
        assertEquals(3, threeYears.value());
    }

    @Test
    void phaseTargetKeepsOnlySemanticTarget() {
        SemanticDuration dawn = SemanticDurationParser.parse("休息直到天亮再说").orElseThrow();

        assertEquals(SemanticDuration.Unit.PHASE, dawn.unit());
        assertEquals("到清晨", dawn.display());
        assertEquals("清晨", dawn.targetPhase());
    }

    @Test
    void ordinaryActionDoesNotInventTimeContract() {
        assertTrue(SemanticDurationParser.parse("查看手掌上的刻痕").isEmpty());
    }

    @Test
    void recognizesNaturalNextDayAndHalfDayWithoutMinutePrecision() {
        SemanticDuration nextDay = SemanticDurationParser.parse("今夜歇息，明日再出发").orElseThrow();
        SemanticDuration halfDay = SemanticDurationParser.parse("继续赶路半日").orElseThrow();
        SemanticDuration oneShichen = SemanticDurationParser.parse("继续修炼一个时辰后停下").orElseThrow();

        assertEquals(SemanticDuration.Unit.DAY, nextDay.unit());
        assertEquals(SemanticDuration.Unit.PHASE, halfDay.unit());
        assertEquals(4, halfDay.value());
        assertEquals(SemanticDuration.Unit.PHASE, oneShichen.unit());
        assertEquals(1, oneShichen.value());
        assertEquals("1个时段", oneShichen.display());
    }

    @Test
    void worldClockAcceptsNaturalNightAlias() {
        SemanticDuration night = new SemanticDuration(SemanticDuration.Unit.PHASE, 1, "夜");

        StoryWorldTime after = StoryWorldTime.opening().advance(night);
        assertEquals(StoryWorldTime.Phase.NIGHT, after.phase());
    }
}

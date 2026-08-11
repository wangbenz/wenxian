package com.wenxian.game.story.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExplicitDurationParserTest {

    @Test
    void treatsSecondDayDawnAsTargetTimeInsteadOfTwoDayDuration() {
        long current = 36L * 1440 + 11 * 60 + 12;

        assertEquals(18 * 60 + 48,
                ExplicitDurationParser.parseMinutes("休息到第二天清晨，醒来后停下", current));
    }

    @Test
    void doesNotReadOrdinalDayAsDuration() {
        assertNull(ExplicitDurationParser.parseMinutes("休息到第三天再看情况", 10 * 60));
    }

    @Test
    void keepsExplicitDurationParsing() {
        assertEquals(3 * 360 * 1440,
                ExplicitDurationParser.parseMinutes("闭关三年后出关", 0));
        assertEquals(15, ExplicitDurationParser.parseMinutes("休息一刻钟", 0));
        assertEquals(120, ExplicitDurationParser.parseMinutes("在门前原地静立一个时辰后睁眼", 0));
    }
}

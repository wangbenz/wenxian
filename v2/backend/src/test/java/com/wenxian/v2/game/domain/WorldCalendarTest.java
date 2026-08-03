package com.wenxian.v2.game.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorldCalendarTest {
    @Test
    void convertsAcrossYearsWithoutLosingMinutes() {
        long minute = WorldCalendar.toWorldMinute(3001, 12, 30, 23 * 60 + 59);
        assertThat(WorldCalendar.fromWorldMinute(minute))
                .isEqualTo(new WorldCalendar.WorldDate(3001, 12, 30, 23, 59));
        assertThat(WorldCalendar.fromWorldMinute(minute + 1))
                .isEqualTo(new WorldCalendar.WorldDate(3002, 1, 1, 0, 0));
    }

    @Test
    void convertsExplicitDurationsWithTheWorldCalendarInsteadOfEarthYears() {
        assertThat(WorldCalendar.durationMinutes(3L, "YEAR", 1_576_800L))
                .isEqualTo(3L * WorldCalendar.MINUTES_PER_YEAR);
        assertThat(WorldCalendar.durationMinutes(2L, "MONTH", null))
                .isEqualTo(60L * WorldCalendar.MINUTES_PER_DAY);
    }

    @Test
    void resolvesNamedPeriodsAgainstTheCurrentWorldDay() {
        long morning = WorldCalendar.toWorldMinute(3001, 1, 2, 9 * 60 + 30);
        assertThat(WorldCalendar.durationUntilNamedPeriod(morning, "等待到午后再看谁来取货"))
                .isEqualTo(4 * 60 + 30L);
        long evening = WorldCalendar.toWorldMinute(3001, 1, 2, 19 * 60);
        assertThat(WorldCalendar.durationUntilNamedPeriod(evening, "休息到第二日清晨"))
                .isEqualTo(11 * 60L);
        assertThat(WorldCalendar.durationUntilNamedPeriod(evening, "等一阵再说"))
                .isNull();
    }
}

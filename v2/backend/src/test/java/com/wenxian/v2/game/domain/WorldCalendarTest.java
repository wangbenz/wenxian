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
}

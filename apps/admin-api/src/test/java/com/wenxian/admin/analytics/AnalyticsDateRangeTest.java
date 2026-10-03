package com.wenxian.admin.analytics;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalyticsDateRangeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);

    @Test
    void resolvesAnExplicitHistoricalRangeWithoutMovingItToToday() {
        AnalyticsDateRange range = AnalyticsDateRange.resolve(
                "2024-01-10", "2024-02-08", null, 14, TODAY);

        assertEquals(LocalDate.of(2024, 1, 10), range.from());
        assertEquals(LocalDate.of(2024, 2, 8), range.to());
        assertEquals(30, range.days());
    }

    @Test
    void keepsLegacyDaysButRejectsSilentTruncation() {
        AnalyticsDateRange range = AnalyticsDateRange.resolve(null, null, 90, 14, TODAY);

        assertEquals(TODAY.minusDays(89), range.from());
        assertEquals(TODAY, range.to());
        assertThrows(IllegalArgumentException.class,
                () -> AnalyticsDateRange.resolve(null, null, 367, 14, TODAY));
    }

    @Test
    void rejectsIncompleteOrReversedRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> AnalyticsDateRange.resolve("2024-01-01", null, null, 14, TODAY));
        assertThrows(IllegalArgumentException.class,
                () -> AnalyticsDateRange.resolve("2024-02-01", "2024-01-01", null, 14, TODAY));
    }
}

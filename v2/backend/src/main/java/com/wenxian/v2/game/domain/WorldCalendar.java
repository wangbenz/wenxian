package com.wenxian.v2.game.domain;

import java.util.Locale;

public final class WorldCalendar {
    public static final int MONTHS_PER_YEAR = 12;
    public static final int DAYS_PER_MONTH = 30;
    public static final int MINUTES_PER_DAY = 24 * 60;
    public static final long DAYS_PER_YEAR = (long) MONTHS_PER_YEAR * DAYS_PER_MONTH;
    public static final long MINUTES_PER_YEAR = DAYS_PER_YEAR * MINUTES_PER_DAY;

    private WorldCalendar() {
    }

    public static long toWorldMinute(int year, int month, int day, int minuteOfDay) {
        if (year < 0 || month < 1 || month > MONTHS_PER_YEAR || day < 1 || day > DAYS_PER_MONTH
                || minuteOfDay < 0 || minuteOfDay >= MINUTES_PER_DAY) {
            throw new IllegalArgumentException("世界时间无效");
        }
        long days = (long) year * DAYS_PER_YEAR + (long) (month - 1) * DAYS_PER_MONTH + day - 1;
        return Math.addExact(Math.multiplyExact(days, MINUTES_PER_DAY), minuteOfDay);
    }

    public static WorldDate fromWorldMinute(long worldMinute) {
        if (worldMinute < 0) throw new IllegalArgumentException("世界时间不能为负数");
        long days = worldMinute / MINUTES_PER_DAY;
        int minuteOfDay = Math.toIntExact(worldMinute % MINUTES_PER_DAY);
        int year = Math.toIntExact(days / DAYS_PER_YEAR);
        long dayWithinYear = days % DAYS_PER_YEAR;
        int month = Math.toIntExact(dayWithinYear / DAYS_PER_MONTH) + 1;
        int day = Math.toIntExact(dayWithinYear % DAYS_PER_MONTH) + 1;
        return new WorldDate(year, month, day, minuteOfDay / 60, minuteOfDay % 60);
    }

    public static Long durationMinutes(Long amount, String unit, Long minuteFallback) {
        if (amount == null && (unit == null || unit.isBlank())) return minuteFallback;
        if (amount == null || amount <= 0 || unit == null || unit.isBlank()) return null;
        long unitMinutes = switch (unit.strip().toUpperCase(Locale.ROOT)) {
            case "MINUTE" -> 1L;
            case "HOUR" -> 60L;
            case "DAY" -> MINUTES_PER_DAY;
            case "MONTH" -> (long) DAYS_PER_MONTH * MINUTES_PER_DAY;
            case "YEAR" -> MINUTES_PER_YEAR;
            default -> 0L;
        };
        if (unitMinutes == 0) return null;
        try {
            return Math.multiplyExact(amount, unitMinutes);
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    /**
     * Resolves a player's explicit wait target such as “等到午后” against the single world clock.
     * This is a closed calendar vocabulary, not a general action-intent keyword classifier.
     */
    public static Long durationUntilNamedPeriod(long currentWorldMinute, String actionText) {
        if (currentWorldMinute < 0 || actionText == null || actionText.isBlank()) return null;
        String text = actionText.strip();
        Integer targetMinute = null;
        if (text.contains("清晨") || text.contains("天亮")) targetMinute = 6 * 60;
        else if (text.contains("上午")) targetMinute = 9 * 60;
        else if (text.contains("正午") || text.contains("中午")) targetMinute = 12 * 60;
        else if (text.contains("午后")) targetMinute = 14 * 60;
        else if (text.contains("傍晚") || text.contains("黄昏")) targetMinute = 18 * 60;
        else if (text.contains("入夜")) targetMinute = 20 * 60;
        else if (text.contains("深夜")) targetMinute = 23 * 60;
        if (targetMinute == null) return null;

        long minuteOfDay = currentWorldMinute % MINUTES_PER_DAY;
        long dayStart = currentWorldMinute - minuteOfDay;
        boolean explicitlyNextDay = text.contains("次日") || text.contains("明日")
                || text.contains("第二日") || text.contains("翌日");
        long target = dayStart + targetMinute;
        if (explicitlyNextDay || target <= currentWorldMinute) target += MINUTES_PER_DAY;
        return target - currentWorldMinute;
    }

    public record WorldDate(int year, int month, int day, int hour, int minute) {
        public String display() {
            return "绝天后%d年·第%d月·第%d日·%02d:%02d".formatted(year, month, day, hour, minute);
        }
    }
}

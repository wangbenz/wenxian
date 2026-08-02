package com.wenxian.v2.game.domain;

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

    public record WorldDate(int year, int month, int day, int hour, int minute) {
        public String display() {
            return "绝天后%d年·第%d月·第%d日·%02d:%02d".formatted(year, month, day, hour, minute);
        }
    }
}

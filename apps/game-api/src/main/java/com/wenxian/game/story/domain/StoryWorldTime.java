package com.wenxian.game.story.domain;

import java.util.Locale;

/**
 * 命卷唯一权威世界时钟。
 *
 * <p>时间只精确到纪年、日序和时段，不保存小时或分钟。普通场景可以在同一时段内连续发生；
 * 过夜、赶路和闭关必须通过 {@link SemanticDuration} 显式推进。本对象可以转换为单调递增的时序号，
 * 仅供数据库排序字段使用，该时序号不代表分钟，也不得进入 Prompt 或玩家界面。</p>
 */
public record StoryWorldTime(int year, int day, Phase phase) implements Comparable<StoryWorldTime> {
    public static final int DAYS_PER_YEAR = 360;
    private static final int PHASES_PER_DAY = Phase.values().length;

    public StoryWorldTime {
        if (year < 1) throw new IllegalArgumentException("世界纪年必须从第一年开始");
        if (day < 1 || day > DAYS_PER_YEAR) throw new IllegalArgumentException("世界日序必须在1到360之间");
        if (phase == null) throw new IllegalArgumentException("世界时间缺少时段");
    }

    /** 新命卷的默认开场时间。 */
    public static StoryWorldTime opening() {
        return new StoryWorldTime(1, 1, Phase.MORNING);
    }

    /** 使用语义时长推进；SCENE 表示同一时段内的新场景，因此不改动时钟。 */
    public StoryWorldTime advance(SemanticDuration duration) {
        if (duration == null) throw new IllegalArgumentException("世界时间推进缺少语义时长");
        return switch (duration.unit()) {
            case SCENE -> this;
            case PHASE -> duration.targetPhase().isBlank()
                    ? plusPhases(duration.value()) : advanceTo(Phase.parse(duration.targetPhase()));
            case DAY -> plusDays(duration.value());
            case MONTH -> plusDays(Math.multiplyExact(duration.value(), 30));
            case YEAR -> plusYears(duration.value());
        };
    }

    public StoryWorldTime plusDays(long days) {
        if (days < 0) throw new IllegalArgumentException("不能倒退世界时间");
        long absoluteDay = Math.addExact(Math.multiplyExact((long) year - 1, DAYS_PER_YEAR), day - 1L);
        long next = Math.addExact(absoluteDay, days);
        int nextYear = Math.toIntExact(Math.floorDiv(next, DAYS_PER_YEAR) + 1);
        int nextDay = (int) Math.floorMod(next, DAYS_PER_YEAR) + 1;
        return new StoryWorldTime(nextYear, nextDay, phase);
    }

    public StoryWorldTime plusYears(int years) {
        if (years < 0) throw new IllegalArgumentException("不能倒退世界时间");
        return new StoryWorldTime(Math.addExact(year, years), day, phase);
    }

    public StoryWorldTime plusPhases(int phases) {
        if (phases < 0) throw new IllegalArgumentException("不能倒退世界时间");
        long next = Math.addExact(toOrder(), phases);
        return fromOrder(next);
    }

    /** 推进到指定时段；目标已过去或等于当前时段时进入下一日。 */
    public StoryWorldTime advanceTo(Phase target) {
        int delta = target.ordinal() - phase.ordinal();
        if (delta <= 0) delta += PHASES_PER_DAY;
        return plusPhases(delta);
    }

    /** 数据库排序专用的语义时序号，不代表分钟。 */
    public long toOrder() {
        long absoluteDay = Math.addExact(Math.multiplyExact((long) year - 1, DAYS_PER_YEAR), day - 1L);
        return Math.addExact(Math.multiplyExact(absoluteDay, PHASES_PER_DAY), phase.ordinal());
    }

    public static StoryWorldTime fromOrder(long order) {
        if (order < 0) throw new IllegalArgumentException("世界时序号不能为负数");
        long absoluteDay = Math.floorDiv(order, PHASES_PER_DAY);
        int phaseIndex = (int) Math.floorMod(order, PHASES_PER_DAY);
        int year = Math.toIntExact(Math.floorDiv(absoluteDay, DAYS_PER_YEAR) + 1);
        int day = (int) Math.floorMod(absoluteDay, DAYS_PER_YEAR) + 1;
        return new StoryWorldTime(year, day, Phase.values()[phaseIndex]);
    }

    public String display() {
        return "世界历" + year + "年 · 第" + day + "日 · " + phase.label();
    }

    @Override
    public int compareTo(StoryWorldTime other) {
        return Long.compare(toOrder(), other.toOrder());
    }

    public enum Phase {
        DAWN("清晨"),
        MORNING("上午"),
        NOON("正午"),
        AFTERNOON("午后"),
        DUSK("黄昏"),
        NIGHT("入夜"),
        LATE_NIGHT("夜深");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Phase parse(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("目标时段不能为空");
            String normalized = value.strip().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "清晨", "DAWN" -> DAWN;
                case "上午", "MORNING" -> MORNING;
                case "正午", "NOON", "MIDDAY" -> NOON;
                case "午后", "下午", "AFTERNOON" -> AFTERNOON;
                case "黄昏", "傍晚", "DUSK", "EVENING" -> DUSK;
                case "入夜", "夜晚", "夜", "NIGHT" -> NIGHT;
                case "夜深", "深夜", "MIDNIGHT", "LATE_NIGHT" -> LATE_NIGHT;
                default -> throw new IllegalArgumentException("不支持的目标时段：" + value);
            };
        }
    }
}

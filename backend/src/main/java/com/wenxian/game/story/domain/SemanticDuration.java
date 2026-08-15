package com.wenxian.game.story.domain;

import java.util.Locale;

/**
 * 玩家授权的叙事时间尺度。
 *
 * <p>玩家与 Agent 使用场景、时段、日、月、年表达时间，分钟仅在旧投影提交时由本对象换算。
 * 本对象不决定行动是否完成，也不要求普通交谈和观察每回合推进固定分钟。</p>
 */
public record SemanticDuration(Unit unit, int value, String targetPhase) {

    public SemanticDuration {
        if (unit == null) throw new IllegalArgumentException("语义时间缺少单位");
        if (value < 1) throw new IllegalArgumentException("语义时间数量必须大于0");
        targetPhase = targetPhase == null ? "" : targetPhase.strip();
        if (unit != Unit.PHASE && !targetPhase.isBlank()) {
            throw new IllegalArgumentException("只有时段推进可以指定目标时段");
        }
    }

    /** 转换为现有世界时间投影使用的兼容分钟值；该值不得暴露给玩家或作为叙事审阅依据。 */
    public long toCompatibilityMinutes(long currentWorldTimeMinutes) {
        return switch (unit) {
            case SCENE -> value;
            case PHASE -> targetPhase.isBlank()
                    ? Math.multiplyExact(value, 180L)
                    : untilPhase(currentWorldTimeMinutes, targetPhase);
            case DAY -> Math.multiplyExact(value, 1_440L);
            case MONTH -> Math.multiplyExact(value, 30L * 1_440L);
            case YEAR -> Math.multiplyExact(value, 360L * 1_440L);
        };
    }

    /** 面向 Prompt、诊断和 H5 的稳定中文表达。 */
    public String display() {
        if (unit == Unit.PHASE && !targetPhase.isBlank()) return "到" + targetPhase;
        String label = switch (unit) {
            case SCENE -> "个场景";
            case PHASE -> "个时段";
            case DAY -> "日";
            case MONTH -> "个月";
            case YEAR -> "年";
        };
        return value + label;
    }

    private long untilPhase(long currentWorldTimeMinutes, String phase) {
        int targetMinute = switch (phase.toUpperCase(Locale.ROOT)) {
            case "清晨", "DAWN" -> 6 * 60;
            case "上午", "MORNING" -> 9 * 60;
            case "正午", "NOON" -> 12 * 60;
            case "午后", "下午", "AFTERNOON" -> 15 * 60;
            case "黄昏", "傍晚", "DUSK" -> 18 * 60;
            case "入夜", "夜晚", "NIGHT" -> 21 * 60;
            case "夜深", "深夜", "MIDNIGHT" -> 0;
            default -> throw new IllegalArgumentException("不支持的目标时段：" + phase);
        };
        int currentMinute = (int) Math.floorMod(currentWorldTimeMinutes, 1_440L);
        int delta = targetMinute - currentMinute;
        return delta > 0 ? delta : 1_440L + delta;
    }

    public enum Unit {
        SCENE,
        PHASE,
        DAY,
        MONTH,
        YEAR
    }
}

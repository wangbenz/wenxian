package com.wenxian.game.story.domain;

/**
 * 玩家授权的叙事时间尺度。
 *
 * <p>玩家与 Agent 只使用场景、时段、日、月、年表达时间。本对象不包含小时或分钟，
 * 也不要求普通交谈和观察每回合推进固定时间。</p>
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

    public enum Unit {
        SCENE,
        PHASE,
        DAY,
        MONTH,
        YEAR
    }
}

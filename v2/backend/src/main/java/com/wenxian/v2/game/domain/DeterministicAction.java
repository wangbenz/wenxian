package com.wenxian.v2.game.domain;

import java.util.Locale;

public enum DeterministicAction {
    WAIT,
    TRAVEL;

    public static DeterministicAction parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("行动类型不能为空");
        return switch (raw.strip().toUpperCase(Locale.ROOT)) {
            case "WAIT" -> WAIT;
            case "TRAVEL" -> TRAVEL;
            default -> throw new IllegalArgumentException("当前无AI内核只支持等待与移动");
        };
    }
}

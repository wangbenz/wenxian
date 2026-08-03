package com.wenxian.v2.game.domain;

import java.util.Locale;

public enum PlayerActionType {
    WAIT,
    TRAVEL,
    FREE;

    public static PlayerActionType parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("行动类型不能为空");
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unsupported) {
            throw new IllegalArgumentException("当前行动类型不受支持");
        }
    }
}

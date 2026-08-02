package com.wenxian.v2.game.domain;

import java.util.Locale;

public enum Difficulty {
    NORMAL,
    HARD;

    public static Difficulty parse(String raw) {
        if (raw == null || raw.isBlank()) return NORMAL;
        return switch (raw.strip().toUpperCase(Locale.ROOT)) {
            case "NORMAL", "普通" -> NORMAL;
            case "HARD", "困难" -> HARD;
            default -> throw new IllegalArgumentException("难度只能是普通或困难");
        };
    }
}

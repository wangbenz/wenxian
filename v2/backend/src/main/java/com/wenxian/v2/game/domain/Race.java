package com.wenxian.v2.game.domain;

import java.util.Locale;

public enum Race {
    HUMAN,
    DEMON,
    SPIRIT;

    public static Race parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("种族不能为空");
        return switch (raw.strip().toUpperCase(Locale.ROOT)) {
            case "HUMAN", "人族" -> HUMAN;
            case "DEMON", "妖族" -> DEMON;
            case "SPIRIT", "灵族" -> SPIRIT;
            default -> throw new IllegalArgumentException("暂不支持该种族");
        };
    }
}

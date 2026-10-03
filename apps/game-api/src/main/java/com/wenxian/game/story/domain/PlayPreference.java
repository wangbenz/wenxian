package com.wenxian.game.story.domain;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 玩家可独立调整的游玩偏好。
 *
 * <p>偏好只影响场景比重、叙事节奏和推荐方向，不是客观事实，不能被解释为玩家已经授权的行动，
 * 也不保证行动成功或特定人物态度。</p>
 */
public record PlayPreference(
        List<Focus> focus,
        CombatFrequency combatFrequency,
        Pace pace,
        Guidance guidance,
        String additionalNote,
        Instant updatedAt
) {
    public PlayPreference {
        focus = focus == null ? List.of() : List.copyOf(new LinkedHashSet<>(focus));
        if (focus.isEmpty()) throw new IllegalArgumentException("游玩关注方向不能为空");
        if (combatFrequency == null) throw new IllegalArgumentException("战斗频率不能为空");
        if (pace == null) throw new IllegalArgumentException("剧情节奏不能为空");
        if (guidance == null) throw new IllegalArgumentException("引导强度不能为空");
        additionalNote = additionalNote == null ? "" : additionalNote.strip();
        if (updatedAt == null) throw new IllegalArgumentException("偏好更新时间不能为空");
    }

    /** 没有额外说明时的中性偏好，不暗示角色已经采取任何行动。 */
    public static PlayPreference defaults(Instant updatedAt) {
        return new PlayPreference(List.of(Focus.EXPLORATION, Focus.CULTIVATION),
                CombatFrequency.BALANCED, Pace.BALANCED, Guidance.BALANCED, "", updatedAt);
    }

    public enum Focus {
        EXPLORATION,
        RELATIONSHIP,
        CULTIVATION,
        MANAGEMENT,
        COMBAT
    }

    public enum CombatFrequency {
        LOW,
        BALANCED,
        HIGH
    }

    public enum Pace {
        RELAXED,
        BALANCED,
        FAST
    }

    public enum Guidance {
        LIGHT,
        BALANCED,
        EXPLICIT
    }
}

package com.wenxian.game.engine;

import java.util.List;

/**
 * Authoritative Java verdict supplied to the prose Agent and persisted with the turn.
 */
public record ConflictVerdict(
        String verdict,
        String powerRelation,
        String playerConditionAfter,
        boolean allowsDeath,
        List<String> facts,
        List<String> constraints
) {
    public static ConflictVerdict narrativeOnly() {
        return new ConflictVerdict("NARRATIVE", "NOT_APPLICABLE", null, false, List.of(), List.of(
                "不得把本轮普通行动擅自改写为主角死亡"
        ));
    }
}

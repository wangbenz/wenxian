package com.wenxian.game.engine;

import com.wenxian.game.model.GameState;

import java.util.List;
import java.util.Set;

/**
 * The sole authority for player major-realm transitions.
 *
 * Narrative prose is deliberately excluded: mentions of another person's realm,
 * future possibilities and hypothetical breakthroughs must never mutate player state.
 */
public final class RealmProgression {
    private static final List<String> REALMS = List.of(
            "凡俗", "引气", "筑基", "金丹", "元婴", "化神", "炼虚", "合道"
    );
    private static final Set<String> RESULTS = Set.of("NONE", "FAILED", "SUCCESS");

    private RealmProgression() {}

    public static AgentResult.RealmTransition validate(
            AgentResult.RealmTransition transition, GameState state, ActionIntent intent
    ) {
        if (transition == null) return null;
        String result = normalizeResult(transition.result());
        if (!"SUCCESS".equals(result)) return null;
        if (state == null || state.player == null) {
            throw new RealmValidationException("REALM_PLAYER_MISSING", "主角状态不存在，不能变更境界");
        }
        if (intent == null || !"CULTIVATE".equals(intent.actionType()) || !intent.breakthroughAttempt()) {
            throw new RealmValidationException("REALM_NOT_AUTHORIZED", "本轮不是明确的突破行动，不能改变境界");
        }

        String current = normalizeRealm(state.player.realm);
        String from = normalizeRealm(transition.fromRealm());
        String to = normalizeRealm(transition.toRealm());
        if (current == null || from == null || to == null) {
            throw new RealmValidationException("REALM_VALUE_INVALID", "境界迁移字段无效");
        }
        if (!current.equals(from)) {
            throw new RealmValidationException("REALM_ORIGIN_MISMATCH", "境界迁移起点与当前状态不一致");
        }
        String expected = nextRealm(current);
        if (expected == null || !expected.equals(to)) {
            throw new RealmValidationException("REALM_TARGET_INVALID", "境界只能依次突破到下一大境界");
        }
        if (transition.reason() == null || transition.reason().isBlank()) {
            throw new RealmValidationException("REALM_REASON_MISSING", "境界迁移缺少突破结果说明");
        }
        return new AgentResult.RealmTransition("SUCCESS", current, expected, transition.reason().strip());
    }

    public static void apply(GameState.PlayerState player, AgentResult.RealmTransition transition) {
        if (transition == null) return;
        if (player == null || !"SUCCESS".equals(transition.result())) {
            throw new IllegalStateException("未校验的境界迁移不能写入状态");
        }
        String current = normalizeRealm(player.realm);
        String from = normalizeRealm(transition.fromRealm());
        String to = normalizeRealm(transition.toRealm());
        if (current == null || from == null || to == null
                || !current.equals(from) || !to.equals(nextRealm(current))) {
            throw new IllegalStateException("境界迁移与当前权威状态不一致");
        }
        player.realm = to;
    }

    static String nextRealm(String realm) {
        String normalized = normalizeRealm(realm);
        int index = REALMS.indexOf(normalized);
        return index < 0 || index >= REALMS.size() - 1 ? null : REALMS.get(index + 1);
    }

    private static String normalizeResult(String value) {
        String normalized = value == null ? "NONE" : value.strip().toUpperCase();
        if (!RESULTS.contains(normalized)) {
            throw new RealmValidationException("REALM_RESULT_INVALID", "境界迁移结果无效");
        }
        return normalized;
    }

    private static String normalizeRealm(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.contains("练气") || normalized.contains("炼气")) return "引气";
        return REALMS.stream().filter(normalized::contains).findFirst().orElse(null);
    }

    public static final class RealmValidationException extends IllegalArgumentException {
        private final String code;

        public RealmValidationException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

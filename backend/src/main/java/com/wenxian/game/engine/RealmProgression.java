package com.wenxian.game.engine;

import com.wenxian.game.model.GameState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the authoritative realm field consistent with explicit breakthrough evidence.
 * It intentionally ignores vague cultivation language so narration alone cannot grant levels.
 */
public final class RealmProgression {
    private static final List<String> REALMS = List.of("凡俗", "引气", "筑基", "金丹", "元婴", "化神", "炼虚", "合道");

    private RealmProgression() {}

    public static Map<String, Object> completePatch(GameState state, Map<String, Object> patch,
                                                     List<String> narrative, List<String> signals,
                                                     AgentResult.AbilityChange abilityChange) {
        Map<String, Object> completed = new LinkedHashMap<>(patch == null ? Map.of() : patch);
        if (state.player == null) return completed;
        if (completed.containsKey("realm")) {
            String normalized = normalizeRealmName(String.valueOf(completed.get("realm")));
            if (normalized != null) completed.put("realm", normalized);
            return completed;
        }

        List<String> evidence = new ArrayList<>();
        if (narrative != null) evidence.addAll(narrative);
        if (signals != null) evidence.addAll(signals);
        if (abilityChange != null) {
            evidence.add(abilityChange.name());
            evidence.add(abilityChange.stage());
            evidence.add(abilityChange.source());
            evidence.add(abilityChange.description());
        }
        String inferred = inferNextRealm(state.player.realm, evidence);
        if (inferred != null) completed.put("realm", inferred);
        return completed;
    }

    public static boolean repairLegacyState(GameState state) {
        if (state == null || state.player == null || state.abilities == null) return false;
        List<String> evidence = new ArrayList<>();
        state.abilities.forEach(ability -> {
            evidence.add(ability.name);
            evidence.add(ability.stage);
            evidence.add(ability.source);
            evidence.add(ability.description);
        });
        String inferred = inferNextRealm(state.player.realm, evidence);
        if (inferred == null) return false;
        state.player.realm = inferred;
        return true;
    }

    private static String inferNextRealm(String currentRealm, List<String> evidence) {
        int current = REALMS.indexOf(currentRealm);
        if (current < 0 || current >= REALMS.size() - 1) return null;
        String next = REALMS.get(current + 1);
        return evidence.stream().filter(value -> value != null && !value.isBlank())
                .anyMatch(value -> explicitlyReaches(value, next)) ? next : null;
    }

    private static boolean explicitlyReaches(String text, String realm) {
        return switch (realm) {
            case "引气" -> containsAny(text, "练气一层", "炼气一层", "引气一层", "踏入引气", "突破引气", "晋入引气", "引气成功");
            case "筑基" -> containsAny(text, "突破筑基", "筑基成功", "筑基已成", "晋入筑基", "踏入筑基");
            case "金丹" -> containsAny(text, "凝结金丹", "结成金丹", "结丹成功", "踏入金丹", "晋入金丹");
            case "元婴" -> containsAny(text, "元婴已成", "凝成元婴", "突破元婴", "踏入元婴", "晋入元婴");
            case "化神" -> containsAny(text, "化神已成", "突破化神", "踏入化神", "晋入化神");
            case "炼虚" -> containsAny(text, "突破炼虚", "踏入炼虚", "晋入炼虚");
            case "合道" -> containsAny(text, "突破合道", "踏入合道", "晋入合道");
            default -> false;
        };
    }

    private static String normalizeRealmName(String proposed) {
        if (proposed == null) return null;
        String value = proposed.strip();
        if (containsAny(value, "练气", "炼气")) return "引气";
        return REALMS.stream().filter(value::contains).findFirst().orElse(null);
    }

    private static boolean containsAny(String text, String... markers) {
        for (String marker : markers) if (text.contains(marker)) return true;
        return false;
    }
}

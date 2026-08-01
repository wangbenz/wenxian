package com.wenxian.game.engine;

import com.wenxian.game.model.GameState;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Binds semantic references produced by the model back to authoritative save entities.
 *
 * <p>This deliberately uses exact, normalized identifiers and names instead of fuzzy or
 * vector similarity. A model may describe an entity, but it cannot invent the identity of
 * an owned ability, item or known character.</p>
 */
public final class AuthoritativeEntityResolver {
    private AuthoritativeEntityResolver() {}

    public static IntentResolution resolveIntent(ActionIntent source, String action, GameState state) {
        if (source == null) return new IntentResolution(null, null);
        List<String> resolvedAbilities = new ArrayList<>();
        for (String reference : safe(source.usedAbilityIds())) {
            Match<GameState.AbilityRecord> match = ability(state, reference, null, null, action);
            if (match.ambiguous()) return ambiguous("ABILITY_REFERENCE_AMBIGUOUS", "所指功法并不唯一",
                    "当前命卷中有多项能力都可能对应这次行动，请直接写出完整的天赋或功法名称。",
                    match.labels());
            resolvedAbilities.add(match.found() ? match.value().id : reference);
        }
        if (resolvedAbilities.isEmpty() && usesAbility(source)) {
            Match<GameState.AbilityRecord> mentioned = mentionedAbilities(state, action);
            if (mentioned.ambiguous()) return ambiguous("ABILITY_REFERENCE_AMBIGUOUS", "所指功法并不唯一",
                    "行动中同时提到了多项已掌握能力，请说明本次主要施展哪一项。", mentioned.labels());
            if (mentioned.found()) resolvedAbilities.add(mentioned.value().id);
        }

        String targetId = source.targetCharacterId();
        String targetName = source.targetName();
        if (usable(targetId) || usable(targetName)) {
            Match<GameState.CharacterRecord> match = character(state, targetId, targetName, action);
            if (match.ambiguous()) return ambiguous("CHARACTER_REFERENCE_AMBIGUOUS", "所指人物并不唯一",
                    "当前记录中有多个人物可能符合描述，请写出完整姓名或身份。", match.labels());
            if (match.found()) {
                targetId = match.value().id;
                targetName = match.value().name;
            }
        }

        List<ActionIntent.ClaimedFact> claims = new ArrayList<>();
        boolean hasRequiredItem = false;
        for (ActionIntent.ClaimedFact claim : safe(source.claims())) {
            if (claim == null) continue;
            String type = value(claim.subjectType(), "BACKGROUND").toUpperCase(Locale.ROOT);
            String subjectId = claim.subjectId();
            if ("REQUIRED_EXISTING".equals(claim.usage()) || claim.requiredForAction()) {
                if ("ABILITY".equals(type)) {
                    Match<GameState.AbilityRecord> match = ability(
                            state, claim.subjectId(), null, claim.value(), action);
                    if (match.ambiguous()) return ambiguous("ABILITY_REFERENCE_AMBIGUOUS", "所指功法并不唯一",
                            "当前命卷中有多项能力都可能符合描述，请写出完整名称。", match.labels());
                    if (match.found()) {
                        subjectId = match.value().id;
                        if (!resolvedAbilities.contains(subjectId) && usesAbility(source)) resolvedAbilities.add(subjectId);
                    }
                } else if ("ITEM".equals(type)) {
                    hasRequiredItem = true;
                    Match<GameState.InventoryItem> match = item(state, claim.subjectId(), null, claim.value(), action);
                    if (match.ambiguous()) return ambiguous("ITEM_REFERENCE_AMBIGUOUS", "所指物品并不唯一",
                            "行囊中有多件物品可能符合描述，请写出完整名称。", match.labels());
                    if (match.found()) subjectId = match.value().id;
                } else if ("CHARACTER".equals(type)) {
                    Match<GameState.CharacterRecord> match = character(state, claim.subjectId(), claim.value(), action);
                    if (match.ambiguous()) return ambiguous("CHARACTER_REFERENCE_AMBIGUOUS", "所指人物并不唯一",
                            "当前记录中有多个人物可能符合描述，请写出完整姓名或身份。", match.labels());
                    if (match.found()) subjectId = match.value().id;
                }
            }
            claims.add(new ActionIntent.ClaimedFact(type, value(subjectId, ""), claim.field(), claim.value(),
                    claim.requiredForAction(), claim.usage()));
        }
        if ("USE_ITEM".equals(source.actionType()) && !hasRequiredItem) {
            Match<GameState.InventoryItem> match = mentionedItems(state, action);
            if (match.ambiguous()) return ambiguous("ITEM_REFERENCE_AMBIGUOUS", "所指物品并不唯一",
                    "行动中可能涉及多件随身物品，请说明本次使用哪一件。", match.labels());
            if (match.found()) {
                claims.add(new ActionIntent.ClaimedFact("ITEM", match.value().id, "existence",
                        match.value().name, true, "REQUIRED_EXISTING"));
            }
        }

        ActionIntent resolved = new ActionIntent(source.actionType(), source.intentMode(), source.breakthroughAttempt(),
                targetId, targetName, source.targetRealm(), source.targetRealmSource(),
                List.copyOf(new LinkedHashSet<>(resolvedAbilities)), source.riskLevel(),
                source.acceptsLethalRisk(), List.copyOf(claims), source.requestedDurationMinutes());
        return new IntentResolution(resolved, null);
    }

    public static Match<GameState.InventoryItem> item(GameState state, String id, String definitionId,
                                                       String name, String action) {
        return match(state == null ? List.of() : state.inventory,
                value -> Arrays.asList(value.id, value.definitionId, value.name),
                value -> value.name, Arrays.asList(id, definitionId, name), action);
    }

    public static Match<GameState.AbilityRecord> ability(GameState state, String id, String definitionId,
                                                          String name, String action) {
        return match(state == null ? List.of() : state.abilities,
                value -> Arrays.asList(value.id, value.definitionId, value.name),
                value -> value.name, Arrays.asList(id, definitionId, name), action);
    }

    public static Match<GameState.CharacterRecord> character(GameState state, String id, String name, String action) {
        return match(state == null ? List.of() : state.characters,
                value -> Arrays.asList(value.id, value.name),
                value -> value.name, Arrays.asList(id, name), action);
    }

    private static Match<GameState.AbilityRecord> mentionedAbilities(GameState state, String action) {
        return match(state == null ? List.of() : state.abilities,
                value -> Arrays.asList(value.id, value.definitionId, value.name),
                value -> value.name, List.of(), action);
    }

    private static Match<GameState.InventoryItem> mentionedItems(GameState state, String action) {
        return match(state == null ? List.of() : state.inventory,
                value -> Arrays.asList(value.id, value.definitionId, value.name),
                value -> value.name, List.of(), action);
    }

    private static <T> Match<T> match(List<T> source, Function<T, List<String>> aliases,
                                      Function<T, String> label, List<String> references, String action) {
        if (source == null || source.isEmpty()) return Match.missing();
        Set<T> exact = new LinkedHashSet<>();
        for (String reference : references) {
            String normalized = normalize(reference);
            if (normalized.isEmpty()) continue;
            for (T value : source) {
                if (value == null) continue;
                if (aliases.apply(value).stream().map(AuthoritativeEntityResolver::normalize)
                        .anyMatch(normalized::equals)) exact.add(value);
            }
        }
        if (!exact.isEmpty()) return result(exact, label);

        String normalizedAction = normalize(action);
        if (normalizedAction.isEmpty()) return Match.missing();
        Set<T> mentions = new LinkedHashSet<>();
        for (T value : source) {
            if (value == null) continue;
            boolean mentioned = aliases.apply(value).stream().map(AuthoritativeEntityResolver::normalize)
                    .filter(alias -> alias.length() >= 2)
                    .anyMatch(normalizedAction::contains);
            if (mentioned) mentions.add(value);
        }
        return result(mentions, label);
    }

    private static <T> Match<T> result(Set<T> matches, Function<T, String> label) {
        if (matches.isEmpty()) return Match.missing();
        List<String> labels = matches.stream().map(label).filter(AuthoritativeEntityResolver::usable).distinct().toList();
        if (matches.size() > 1) return new Match<>(null, true, labels);
        return new Match<>(matches.iterator().next(), false, labels);
    }

    private static IntentResolution ambiguous(String code, String title, String reason, List<String> labels) {
        String options = labels == null || labels.isEmpty() ? "" : "可能是：" + String.join("、", labels) + "。";
        return new IntentResolution(null, new ResolutionIssue(code, title, reason + options,
                List.of("写出完整名称后重新行动", "查看当前角色记录", "改用眼前明确的目标")));
    }

    private static boolean usesAbility(ActionIntent intent) {
        return intent != null && Set.of("EXECUTE", "ATTEMPT").contains(intent.intentMode())
                && Set.of("USE_ABILITY", "COMBAT", "ESCAPE", "INFILTRATE").contains(intent.actionType());
    }

    private static String normalize(String value) {
        if (!usable(value)) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s《》〈〉『』「」【】()（）\\[\\]·._—\\-]", "");
    }

    private static boolean usable(String value) {
        return value != null && !value.isBlank();
    }

    private static String value(String candidate, String fallback) {
        return usable(candidate) ? candidate.strip() : fallback;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record IntentResolution(ActionIntent intent, ResolutionIssue issue) {}

    public record ResolutionIssue(String code, String title, String reason, List<String> suggestions) {}

    public record Match<T>(T value, boolean ambiguous, List<String> labels) {
        public static <T> Match<T> missing() { return new Match<>(null, false, List.of()); }
        public boolean found() { return value != null; }
    }

    public static final class EntityValidationException extends IllegalArgumentException {
        private final String code;

        public EntityValidationException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

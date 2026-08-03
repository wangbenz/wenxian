package com.wenxian.v2.ai.domain;

import java.util.List;
import java.util.Locale;

public record ActionIntent(
        String actionType,
        String intentMode,
        Long durationMinutes,
        String targetLocationId,
        String targetCharacterId,
        List<String> referencedItemIds,
        List<String> referencedAbilityIds,
        String targetRealmCode,
        boolean declaresGuaranteedOutcome,
        List<String> ambiguities,
        String summary,
        Long durationAmount,
        String durationUnit
) {
    public ActionIntent {
        actionType = normalize(actionType, "OTHER");
        intentMode = normalize(intentMode, "ATTEMPT");
        referencedItemIds = immutable(referencedItemIds);
        referencedAbilityIds = immutable(referencedAbilityIds);
        ambiguities = immutable(ambiguities);
        summary = summary == null ? "" : summary.strip();
        durationUnit = durationUnit == null || durationUnit.isBlank()
                ? null : durationUnit.strip().toUpperCase(Locale.ROOT);
    }

    public ActionIntent(String actionType, String intentMode, Long durationMinutes,
                        String targetLocationId, String targetCharacterId,
                        List<String> referencedItemIds, List<String> referencedAbilityIds,
                        String targetRealmCode, boolean declaresGuaranteedOutcome,
                        List<String> ambiguities, String summary) {
        this(actionType, intentMode, durationMinutes, targetLocationId, targetCharacterId,
                referencedItemIds, referencedAbilityIds, targetRealmCode, declaresGuaranteedOutcome,
                ambiguities, summary, null, null);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip().toUpperCase(Locale.ROOT);
    }

    private static List<String> immutable(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::strip).distinct().toList();
    }
}

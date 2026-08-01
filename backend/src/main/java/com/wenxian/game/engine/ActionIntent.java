package com.wenxian.game.engine;

import java.util.List;

/**
 * Semantic reading of one player action. It contains no gameplay verdict.
 */
public record ActionIntent(
        String actionType,
        String intentMode,
        boolean breakthroughAttempt,
        String targetCharacterId,
        String targetName,
        String targetRealm,
        String targetRealmSource,
        List<String> usedAbilityIds,
        String riskLevel,
        boolean acceptsLethalRisk,
        List<ClaimedFact> claims,
        Integer requestedDurationMinutes
) {
    public ActionIntent(
            String actionType, String intentMode, boolean breakthroughAttempt,
            String targetCharacterId, String targetName, String targetRealm, String targetRealmSource,
            List<String> usedAbilityIds, String riskLevel, boolean acceptsLethalRisk,
            List<ClaimedFact> claims
    ) {
        this(actionType, intentMode, breakthroughAttempt, targetCharacterId, targetName, targetRealm,
                targetRealmSource, usedAbilityIds, riskLevel, acceptsLethalRisk, claims, null);
    }

    public ActionIntent(
            String actionType, String targetCharacterId, String targetName, String targetRealm,
            String targetRealmSource, List<String> usedAbilityIds, String riskLevel,
            boolean acceptsLethalRisk, List<ClaimedFact> claims
    ) {
        this(actionType, "EXECUTE", false, targetCharacterId, targetName, targetRealm, targetRealmSource,
                usedAbilityIds, riskLevel, acceptsLethalRisk, claims, null);
    }

    public ActionIntent(
            String actionType, boolean breakthroughAttempt, String targetCharacterId, String targetName,
            String targetRealm, String targetRealmSource, List<String> usedAbilityIds, String riskLevel,
            boolean acceptsLethalRisk, List<ClaimedFact> claims
    ) {
        this(actionType, "EXECUTE", breakthroughAttempt, targetCharacterId, targetName, targetRealm,
                targetRealmSource, usedAbilityIds, riskLevel, acceptsLethalRisk, claims, null);
    }

    public ActionIntent withRequestedDuration(Integer minutes) {
        return new ActionIntent(actionType, intentMode, breakthroughAttempt, targetCharacterId, targetName,
                targetRealm, targetRealmSource, usedAbilityIds, riskLevel, acceptsLethalRisk, claims, minutes);
    }

    public record ClaimedFact(
            String subjectType,
            String subjectId,
            String field,
            String value,
            boolean requiredForAction,
            String usage
    ) {
        public ClaimedFact(String subjectType, String subjectId, String field, String value,
                           boolean requiredForAction) {
            this(subjectType, subjectId, field, value, requiredForAction,
                    requiredForAction ? "REQUIRED_EXISTING" : "CONTEXT");
        }
    }
}

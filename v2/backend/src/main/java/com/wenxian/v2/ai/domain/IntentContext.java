package com.wenxian.v2.ai.domain;

import java.util.List;

public record IntentContext(
        String userId,
        String saveId,
        String operationId,
        long expectedStateVersion,
        String actionText,
        ProtagonistFacts protagonist,
        String currentLocationId,
        String currentLocationName,
        long currentWorldMinute,
        List<EntityCandidate> characterCandidates,
        List<EntityCandidate> locationCandidates,
        List<EntityCandidate> itemCandidates,
        List<EntityCandidate> abilityCandidates
) {
    public IntentContext {
        characterCandidates = characterCandidates == null ? List.of() : List.copyOf(characterCandidates);
        locationCandidates = locationCandidates == null ? List.of() : List.copyOf(locationCandidates);
        itemCandidates = itemCandidates == null ? List.of() : List.copyOf(itemCandidates);
        abilityCandidates = abilityCandidates == null ? List.of() : List.copyOf(abilityCandidates);
    }

    public record ProtagonistFacts(
            String id,
            String name,
            String race,
            String realmCode,
            String realmName,
            String bodyCondition,
            String currentGoal
    ) {
    }

    public record EntityCandidate(String id, String name, String type, String state) {
    }
}

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
        List<EntityCandidate> abilityCandidates,
        List<EntityCandidate> itemDefinitionCandidates,
        List<EntityCandidate> walletCandidates,
        List<EntityCandidate> abilityDefinitionCandidates
) {
    public IntentContext {
        characterCandidates = characterCandidates == null ? List.of() : List.copyOf(characterCandidates);
        locationCandidates = locationCandidates == null ? List.of() : List.copyOf(locationCandidates);
        itemCandidates = itemCandidates == null ? List.of() : List.copyOf(itemCandidates);
        abilityCandidates = abilityCandidates == null ? List.of() : List.copyOf(abilityCandidates);
        itemDefinitionCandidates = itemDefinitionCandidates == null ? List.of() : List.copyOf(itemDefinitionCandidates);
        walletCandidates = walletCandidates == null ? List.of() : List.copyOf(walletCandidates);
        abilityDefinitionCandidates = abilityDefinitionCandidates == null
                ? List.of() : List.copyOf(abilityDefinitionCandidates);
    }

    public IntentContext(String userId, String saveId, String operationId, long expectedStateVersion,
                         String actionText, ProtagonistFacts protagonist, String currentLocationId,
                         String currentLocationName, long currentWorldMinute,
                         List<EntityCandidate> characterCandidates, List<EntityCandidate> locationCandidates,
                         List<EntityCandidate> itemCandidates, List<EntityCandidate> abilityCandidates) {
        this(userId, saveId, operationId, expectedStateVersion, actionText, protagonist,
                currentLocationId, currentLocationName, currentWorldMinute, characterCandidates,
                locationCandidates, itemCandidates, abilityCandidates, List.of(), List.of(), List.of());
    }

    public record ProtagonistFacts(
            String id,
            String name,
            String race,
            String realmCode,
            String realmName,
            String nextRealmCode,
            String nextRealmName,
            String bodyCondition,
            String currentGoal
    ) {
    }

    public record EntityCandidate(String id, String name, String type, String state, String details) {
        public EntityCandidate(String id, String name, String type, String state) {
            this(id, name, type, state, null);
        }
    }
}

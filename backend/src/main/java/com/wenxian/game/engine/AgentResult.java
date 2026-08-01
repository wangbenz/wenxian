package com.wenxian.game.engine;

import com.wenxian.game.model.GameState;

import java.util.List;
import java.util.Map;

public record AgentResult(
        String sceneId,
        String sceneTitle,
        String currentLocation,
        int elapsed,
        String outcome,
        List<String> narrative,
        List<String> signals,
        List<GameState.StoryChoice> choices,
        Map<String, Object> playerPatch,
        EventDraft event,
        EntityPatch characterPatch,
        EntityPatch threadPatch,
        InventoryChange inventoryChange,
        AbilityChange abilityChange,
        ActionResolution actionResolution,
        DeclarationResolution declarationResolution,
        ActionIntent actionIntent,
        ConflictVerdict adjudication,
        List<CharacterChange> characterChanges,
        List<InventoryChange> inventoryChanges,
        List<AbilityChange> abilityChanges,
        List<EntityPatch> threadChanges,
        LocationTransition locationTransition,
        RealmTransition realmTransition,
        List<CurrencyChange> currencyChanges,
        TimeResolution timeResolution
) {
    /** Compatibility constructor for callers created before explicit time resolution was introduced. */
    public AgentResult(
            String sceneId, String sceneTitle, String currentLocation, int elapsed, String outcome,
            List<String> narrative, List<String> signals, List<GameState.StoryChoice> choices,
            Map<String, Object> playerPatch, EventDraft event, EntityPatch characterPatch,
            EntityPatch threadPatch, InventoryChange inventoryChange, AbilityChange abilityChange,
            ActionResolution actionResolution, DeclarationResolution declarationResolution,
            ActionIntent actionIntent, ConflictVerdict adjudication, List<CharacterChange> characterChanges,
            List<InventoryChange> inventoryChanges, List<AbilityChange> abilityChanges,
            List<EntityPatch> threadChanges, LocationTransition locationTransition,
            RealmTransition realmTransition, List<CurrencyChange> currencyChanges
    ) {
        this(sceneId, sceneTitle, currentLocation, elapsed, outcome, narrative, signals, choices,
                playerPatch, event, characterPatch, threadPatch, inventoryChange, abilityChange,
                actionResolution, declarationResolution, actionIntent, adjudication, characterChanges,
                inventoryChanges, abilityChanges, threadChanges, locationTransition, realmTransition,
                currencyChanges, null);
    }

    public AgentResult(
            String sceneId, String sceneTitle, String currentLocation, int elapsed, String outcome,
            List<String> narrative, List<String> signals, List<GameState.StoryChoice> choices,
            Map<String, Object> playerPatch, EventDraft event, EntityPatch characterPatch,
            EntityPatch threadPatch, InventoryChange inventoryChange, AbilityChange abilityChange,
            ActionResolution actionResolution, DeclarationResolution declarationResolution
    ) {
        this(sceneId, sceneTitle, currentLocation, elapsed, outcome, narrative, signals, choices,
                playerPatch, event, characterPatch, threadPatch, inventoryChange, abilityChange,
                actionResolution, declarationResolution, null, null, List.of(), List.of(), List.of(), List.of(), null, null,
                List.of());
    }

    public AgentResult(
            String sceneId, String sceneTitle, String currentLocation, int elapsed, String outcome,
            List<String> narrative, List<String> signals, List<GameState.StoryChoice> choices,
            Map<String, Object> playerPatch, EventDraft event, EntityPatch characterPatch,
            EntityPatch threadPatch, InventoryChange inventoryChange, AbilityChange abilityChange,
            ActionResolution actionResolution, DeclarationResolution declarationResolution,
            ConflictVerdict adjudication
    ) {
        this(sceneId, sceneTitle, currentLocation, elapsed, outcome, narrative, signals, choices,
                playerPatch, event, characterPatch, threadPatch, inventoryChange, abilityChange,
                actionResolution, declarationResolution, null, adjudication, List.of(), List.of(), List.of(), List.of(), null,
                null, List.of());
    }

    public AgentResult(
            String sceneId, String sceneTitle, String currentLocation, int elapsed, String outcome,
            List<String> narrative, List<String> signals, List<GameState.StoryChoice> choices,
            Map<String, Object> playerPatch, EventDraft event, EntityPatch characterPatch,
            EntityPatch threadPatch, InventoryChange inventoryChange, AbilityChange abilityChange,
            ActionResolution actionResolution, DeclarationResolution declarationResolution,
            ConflictVerdict adjudication, List<CharacterChange> characterChanges
    ) {
        this(sceneId, sceneTitle, currentLocation, elapsed, outcome, narrative, signals, choices,
                playerPatch, event, characterPatch, threadPatch, inventoryChange, abilityChange,
                actionResolution, declarationResolution, null, adjudication, characterChanges,
                List.of(), List.of(), List.of(), null, null, List.of());
    }

    public AgentResult withLocationTransition(LocationTransition transition) {
        return new AgentResult(
                sceneId, sceneTitle, currentLocation, elapsed, outcome, narrative, signals, choices,
                playerPatch, event, characterPatch, threadPatch, inventoryChange, abilityChange,
                actionResolution, declarationResolution, actionIntent, adjudication, characterChanges,
                inventoryChanges, abilityChanges, threadChanges, transition, realmTransition, currencyChanges,
                timeResolution
        );
    }

    public AgentResult withRealmTransition(RealmTransition transition) {
        return new AgentResult(
                sceneId, sceneTitle, currentLocation, elapsed, outcome, narrative, signals, choices,
                playerPatch, event, characterPatch, threadPatch, inventoryChange, abilityChange,
                actionResolution, declarationResolution, actionIntent, adjudication, characterChanges,
                inventoryChanges, abilityChanges, threadChanges, locationTransition, transition, currencyChanges,
                timeResolution
        );
    }

    public AgentResult withCurrencyChanges(List<CurrencyChange> changes) {
        return new AgentResult(
                sceneId, sceneTitle, currentLocation, elapsed, outcome, narrative, signals, choices,
                playerPatch, event, characterPatch, threadPatch, inventoryChange, abilityChange,
                actionResolution, declarationResolution, actionIntent, adjudication, characterChanges,
                inventoryChanges, abilityChanges, threadChanges, locationTransition, realmTransition,
                changes == null ? List.of() : changes, timeResolution
        );
    }

    public AgentResult withTimeResolution(TimeResolution resolution) {
        return new AgentResult(
                sceneId, sceneTitle, currentLocation, elapsed, outcome, narrative, signals, choices,
                playerPatch, event, characterPatch, threadPatch, inventoryChange, abilityChange,
                actionResolution, declarationResolution, actionIntent, adjudication, characterChanges,
                inventoryChanges, abilityChanges, threadChanges, locationTransition, realmTransition,
                currencyChanges, resolution
        );
    }

    public AgentResult(
            String sceneId, String sceneTitle, String currentLocation, int elapsed, String outcome,
            List<String> narrative, List<String> signals, List<GameState.StoryChoice> choices,
            Map<String, Object> playerPatch, EventDraft event, EntityPatch characterPatch,
            EntityPatch threadPatch, InventoryChange inventoryChange, AbilityChange abilityChange,
            ActionResolution actionResolution, DeclarationResolution declarationResolution,
            ActionIntent actionIntent, ConflictVerdict adjudication, List<CharacterChange> characterChanges
    ) {
        this(sceneId, sceneTitle, currentLocation, elapsed, outcome, narrative, signals, choices,
                playerPatch, event, characterPatch, threadPatch, inventoryChange, abilityChange,
                actionResolution, declarationResolution, actionIntent, adjudication, characterChanges,
                List.of(), List.of(), List.of(), null, null, List.of());
    }

    /** Compatibility constructor for callers without a realm transition. */
    public AgentResult(
            String sceneId, String sceneTitle, String currentLocation, int elapsed, String outcome,
            List<String> narrative, List<String> signals, List<GameState.StoryChoice> choices,
            Map<String, Object> playerPatch, EventDraft event, EntityPatch characterPatch,
            EntityPatch threadPatch, InventoryChange inventoryChange, AbilityChange abilityChange,
            ActionResolution actionResolution, DeclarationResolution declarationResolution,
            ActionIntent actionIntent, ConflictVerdict adjudication, List<CharacterChange> characterChanges,
            List<InventoryChange> inventoryChanges, List<AbilityChange> abilityChanges,
            List<EntityPatch> threadChanges, LocationTransition locationTransition
    ) {
        this(sceneId, sceneTitle, currentLocation, elapsed, outcome, narrative, signals, choices,
                playerPatch, event, characterPatch, threadPatch, inventoryChange, abilityChange,
                actionResolution, declarationResolution, actionIntent, adjudication, characterChanges,
                inventoryChanges, abilityChanges, threadChanges, locationTransition, null, List.of());
    }

    public record EventDraft(String title, String detail, String kind) {}
    public record EntityPatch(String id, Map<String, Object> values) {}
    public record InventoryChange(
            String operation, String id, String definitionId, String name, String category,
            String quality, int quantity, String description, boolean keyItem
    ) {}
    public record AbilityChange(
            String operation, String id, String definitionId, String name, String type,
            String stage, int mastery, String source, String description, String rules,
            int maxUses, int remainingUses, String resetRule, String sideEffect
    ) {}
    public record ActionResolution(
            String result, String code, String title, String reason,
            List<String> suggestions, boolean turnConsumed
    ) {
        public ActionResolution(String result, String title, String reason, List<String> suggestions) {
            this(result, "ACCEPTED".equals(result) ? "ACCEPTED" : "UNSPECIFIED",
                    title, reason, suggestions, false);
        }
    }
    public record DeclarationResolution(String type, String result, String reason) {}
    public record CharacterChange(
            String operation, String id, String name, String identity, String realm, String condition,
            String combatProfile, String state, String location, String relation
    ) {}
    public record LocationTransition(
            String fromLocationId, String toLocationId, String toLocationName, String scope,
            String method, String abilityId, String reason
    ) {}

    public record RealmTransition(String result, String fromRealm, String toRealm, String reason) {}
    public record CurrencyChange(String currency, long amount, String reason) {}
    public record TimeResolution(int requestedMinutes, int actualMinutes, boolean completed,
                                 String interruptionReason) {}
}

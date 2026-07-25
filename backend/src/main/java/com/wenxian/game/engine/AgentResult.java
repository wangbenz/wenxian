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
        DeclarationResolution declarationResolution
) {
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
    public record DeclarationResolution(String type, String result, String reason) {}
}

package com.wenxian.game.engine;

import com.wenxian.game.model.GameState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the compact, authoritative state supplied to the prose model.
 *
 * <p>The database keeps the complete save. The model receives a small index of known
 * entities plus full records only for entities involved in the current action. This avoids
 * replaying every long description on every turn without making an entity disappear from
 * the model's known world.</p>
 */
final class GameplayPromptContextAssembler {

    Map<String, Object> assemble(GameState state, String action, ActionIntent intent) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("phase", state.phase);
        context.put("scene", map(
                "id", state.sceneId,
                "title", state.sceneTitle,
                "location", state.currentLocation,
                "locationId", state.currentLocationId
        ));
        context.put("worldTime", map(
                "year", state.worldYear,
                "day", state.day,
                "minute", state.minute,
                "turn", state.turn
        ));
        context.put("world", compactWorld(state.world));
        context.put("player", authoritativePlayer(state.player));
        context.put("wallet", authoritativeWallet(state));
        context.put("currentNarrative", safe(state.narrative));
        context.put("signals", safe(state.signals));
        context.put("activeThreads", compactThreads(state.threads));
        context.put("indexFormat", map(
                "knownCharacters", "id｜name｜identity｜realm｜condition｜location｜relation",
                "inventoryIndex", "id｜definitionId｜name｜quality｜quantity｜keyItem",
                "abilityIndex", "id｜definitionId｜name｜type｜stage｜remaining/max｜resetRule",
                "knownLocations", "id｜name｜scope｜parentId"
        ));
        context.put("knownCharacters", characterIndex(state.characters));
        context.put("relatedCharacters", relatedCharacters(state, action, intent));
        context.put("inventoryIndex", inventoryIndex(state.inventory));
        context.put("relatedItems", relatedItems(state, action, intent));
        context.put("abilityIndex", abilityIndex(state.abilities));
        context.put("relatedAbilities", relatedAbilities(state, action, intent));
        context.put("knownLocations", locationIndex(state.knownLocations));
        return context;
    }

    private Map<String, Object> compactWorld(GameState.WorldProfile world) {
        if (world == null) return Map.of();
        return map(
                "id", world.id,
                "title", world.title,
                "era", world.era,
                "summary", world.summary,
                "cultivationLaw", world.cultivationLaw,
                "dangerRule", world.dangerRule,
                "regions", safe(world.regions),
                "factions", safe(world.factions)
        );
    }

    private Map<String, Object> authoritativePlayer(GameState.PlayerState player) {
        if (player == null) return Map.of();
        return map(
                "name", player.name,
                "race", player.race,
                "gender", player.gender,
                "age", player.age,
                "lifespan", player.lifespan,
                "identity", player.identity,
                "origin", player.origin,
                "trait", player.trait,
                "originPremise", player.originPremise,
                "lifeGoal", player.lifeGoal,
                "goal", player.goal,
                "burden", player.burden,
                "realm", player.realm,
                "constitution", player.constitution,
                "condition", player.condition,
                "insight", player.insight,
                "suspicion", player.suspicion
        );
    }

    private Map<String, Object> authoritativeWallet(GameState state) {
        GameState.Wallet wallet = state.wallet;
        if (wallet == null) {
            long legacyCopper = state.player == null ? 0L : Math.max(0, state.player.silver);
            return map("copperWen", legacyCopper, "lowSpiritStones", 0L,
                    "middleSpiritStones", 0L, "highSpiritStones", 0L);
        }
        return map("copperWen", wallet.copperWen, "lowSpiritStones", wallet.lowSpiritStones,
                "middleSpiritStones", wallet.middleSpiritStones,
                "highSpiritStones", wallet.highSpiritStones);
    }

    private List<Map<String, Object>> compactThreads(List<GameState.QuestThread> threads) {
        return safe(threads).stream().filter(value -> value != null)
                .map(value -> map("id", value.id, "title", value.title,
                        "state", value.state, "danger", value.danger))
                .toList();
    }

    private List<String> characterIndex(List<GameState.CharacterRecord> characters) {
        return safe(characters).stream().filter(value -> value != null)
                .map(value -> join(value.id, value.name, value.identity, value.realm,
                        value.condition, value.location, value.relation))
                .toList();
    }

    private List<GameState.CharacterRecord> relatedCharacters(GameState state, String action, ActionIntent intent) {
        Set<String> ids = new LinkedHashSet<>();
        if (intent != null) add(ids, intent.targetCharacterId());
        claimedIds(ids, intent, "CHARACTER");
        List<GameState.CharacterRecord> result = new ArrayList<>();
        for (GameState.CharacterRecord value : safe(state.characters)) {
            if (value == null) continue;
            boolean related = ids.contains(value.id)
                    || mentioned(action, value.id, value.name)
                    || same(value.location, state.currentLocation);
            if (related) result.add(value);
        }
        return result;
    }

    private List<String> inventoryIndex(List<GameState.InventoryItem> inventory) {
        return safe(inventory).stream().filter(value -> value != null)
                .map(value -> join(value.id, value.definitionId, value.name,
                        value.quality, "数量" + value.quantity, value.keyItem ? "关键" : "普通"))
                .toList();
    }

    private List<GameState.InventoryItem> relatedItems(GameState state, String action, ActionIntent intent) {
        Set<String> ids = new LinkedHashSet<>();
        claimedIds(ids, intent, "ITEM");
        List<GameState.InventoryItem> result = new ArrayList<>();
        for (GameState.InventoryItem value : safe(state.inventory)) {
            if (value == null) continue;
            if (ids.contains(value.id) || ids.contains(value.definitionId)
                    || mentioned(action, value.id, value.definitionId, value.name)) result.add(value);
        }
        return result;
    }

    private List<String> abilityIndex(List<GameState.AbilityRecord> abilities) {
        return safe(abilities).stream().filter(value -> value != null)
                .map(value -> join(value.id, value.definitionId, value.name,
                        value.type, value.stage, value.remainingUses + "/" + value.maxUses,
                        value.resetRule))
                .toList();
    }

    private List<GameState.AbilityRecord> relatedAbilities(GameState state, String action, ActionIntent intent) {
        Set<String> ids = new LinkedHashSet<>();
        if (intent != null) safe(intent.usedAbilityIds()).forEach(value -> add(ids, value));
        claimedIds(ids, intent, "ABILITY");
        List<GameState.AbilityRecord> result = new ArrayList<>();
        for (GameState.AbilityRecord value : safe(state.abilities)) {
            if (value == null) continue;
            if (ids.contains(value.id) || ids.contains(value.definitionId)
                    || mentioned(action, value.id, value.definitionId, value.name)) result.add(value);
        }
        return result;
    }

    private List<String> locationIndex(List<GameState.LocationRecord> locations) {
        return safe(locations).stream().filter(value -> value != null)
                .map(value -> join(value.id, value.name, value.scope, value.parentId))
                .toList();
    }

    private String join(Object... values) {
        List<String> parts = new ArrayList<>();
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) parts.add(String.valueOf(value));
        }
        return String.join("｜", parts);
    }

    private void claimedIds(Set<String> target, ActionIntent intent, String type) {
        if (intent == null) return;
        for (ActionIntent.ClaimedFact claim : safe(intent.claims())) {
            if (claim != null && type.equalsIgnoreCase(claim.subjectType())) add(target, claim.subjectId());
        }
    }

    private boolean mentioned(String action, String... aliases) {
        if (action == null || action.isBlank()) return false;
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank() && action.contains(alias)) return true;
        }
        return false;
    }

    private boolean same(String left, String right) {
        return left != null && right != null && left.strip().equals(right.strip());
    }

    private void add(Set<String> target, String value) {
        if (value != null && !value.isBlank()) target.add(value.strip());
    }

    private <T> List<T> safe(List<T> source) {
        return source == null ? List.of() : source;
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            Object value = values[index + 1];
            if (value != null) result.put(String.valueOf(values[index]), value);
        }
        return result;
    }
}

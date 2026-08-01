package com.wenxian.game.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.GameState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

@Component
public class GameEngine {
    private final ObjectMapper objectMapper;

    public GameEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GameState copy(GameState source) {
        return objectMapper.convertValue(source, GameState.class);
    }

    public GameState apply(GameState current, AgentResult result) {
        GameState next = copy(current);
        // Repair a stale daily counter before applying the turn. This mainly protects
        // legacy saves that were persisted after midnight without being refreshed.
        refreshDailyAbilities(next);
        int absoluteMinute = next.minute + result.elapsed();
        next.day += absoluteMinute / 1440;
        next.minute = absoluteMinute % 1440;
        while (next.day > 360) {
            next.day -= 360;
            next.worldYear += 1;
            if (next.player != null) next.player.age += 1;
        }
        // A turn may itself span one or more days (resting, travelling or seclusion).
        // Refresh again at the resulting world date so the following action can use a
        // DAILY ability immediately, rather than requiring an unrelated extra turn.
        refreshDailyAbilities(next);
        next.turn += 1;
        next.sceneId = result.sceneId();
        next.sceneTitle = result.sceneTitle() == null ? next.sceneTitle : result.sceneTitle();
        applyLocationTransition(next, result);
        next.narrative = new ArrayList<>(result.narrative());
        next.signals = new ArrayList<>(result.signals());
        next.choices = result.choices() == null ? next.choices : new ArrayList<>(result.choices());

        applyPlayerPatch(next.player, result.playerPatch());
        RealmProgression.apply(next.player, result.realmTransition());
        applyCharacterPatch(next, result.characterPatch());
        applyCharacterChanges(next, result.characterChanges());
        applyThreadPatch(next, result.threadPatch());
        applyThreadChanges(next, result.threadChanges());
        applyInventoryChange(next, result.inventoryChange());
        applyInventoryChanges(next, result.inventoryChanges());
        CurrencyAuthority.apply(next, result.currencyChanges());
        applyAbilityChange(next, result.abilityChange());
        applyAbilityChanges(next, result.abilityChanges());
        applyAdjudicatedCondition(next.player, result.adjudication());

        AgentResult.EventDraft draft = result.event();
        next.events.add(0, new GameState.ChronicleEvent(
                "event-" + next.turn + "-" + UUID.randomUUID(),
                draft.title(), draft.detail(), absoluteTime(next), draft.kind()
        ));

        if ("DEAD".equals(result.outcome())
                || result.adjudication() != null && result.adjudication().allowsDeath()) {
            sealDeadLife(next, draft);
        }
        return next;
    }

    public void consumeAbility(GameState state, String abilityId) {
        if (abilityId == null) return;
        state.abilities.stream().filter(ability -> ability.id.equals(abilityId)).findFirst().ifPresent(ability -> {
            if (ability.maxUses > 0) ability.remainingUses = Math.max(0, ability.remainingUses - 1);
        });
    }

    private void sealDeadLife(GameState state, AgentResult.EventDraft finalEvent) {
        state.phase = "dead";
        state.player.vitality = 0;
        state.choices = new ArrayList<>();
        if (state.signals.stream().noneMatch("本世已终"::equals)) {
            state.signals.add("本世已终");
        }
        state.death = new GameState.DeathRecord(
                finalEvent.title(), finalEvent.detail(), absoluteTime(state),
                state.sceneTitle, state.currentLocation, state.turn
        );
    }

    private void applyPlayerPatch(GameState.PlayerState player, Map<String, Object> patch) {
        if (patch == null) return;
        patch.forEach((key, value) -> {
            int number = value instanceof Number n ? n.intValue() : 0;
            switch (key) {
                // Legacy numeric vitality is no longer accepted from narrative output.
                case "vitality" -> { }
                case "condition" -> setCondition(player, String.valueOf(value));
                case "silver" -> throw new IllegalArgumentException("货币不能通过通用角色补丁修改");
                case "insight" -> player.insight = clamp(number, 0, 10);
                case "suspicion" -> player.suspicion = clamp(number, 0, 100);
                case "identity" -> player.identity = String.valueOf(value);
                case "goal" -> player.goal = String.valueOf(value);
                case "burden" -> player.burden = String.valueOf(value);
                case "realm" -> throw new IllegalArgumentException("境界不能通过通用角色补丁修改");
                case "constitution" -> player.constitution = String.valueOf(value);
                default -> { }
            }
        });
    }

    private void applyInventoryChange(GameState state, AgentResult.InventoryChange change) {
        if (change == null) return;
        GameState.InventoryItem existing = state.inventory.stream()
                .filter(item -> item.id.equals(change.id())).findFirst().orElse(null);
        switch (change.operation()) {
            case "ADD" -> {
                if (existing != null) existing.quantity = clamp(existing.quantity + change.quantity(), 0, 9999);
                else state.inventory.add(new GameState.InventoryItem(change.id(), change.definitionId(), change.name(),
                        change.category(), change.quality(), change.quantity(), change.description(), change.keyItem()));
            }
            case "REMOVE" -> {
                if (existing == null || existing.keyItem) return;
                existing.quantity -= change.quantity();
                if (existing.quantity <= 0) state.inventory.remove(existing);
            }
            case "UPDATE" -> {
                if (existing == null) return;
                existing.quantity = clamp(change.quantity(), 1, 9999);
                existing.description = change.description();
                existing.quality = change.quality();
            }
            default -> { }
        }
    }

    private void applyInventoryChanges(GameState state, java.util.List<AgentResult.InventoryChange> changes) {
        if (changes == null) return;
        changes.forEach(change -> applyInventoryChange(state, change));
    }

    private void applyAbilityChange(GameState state, AgentResult.AbilityChange change) {
        if (change == null) return;
        GameState.AbilityRecord existing = state.abilities.stream()
                .filter(item -> item.id.equals(change.id())).findFirst().orElse(null);
        if ("LEARN".equals(change.operation()) && existing == null) {
            state.abilities.add(new GameState.AbilityRecord(change.id(), change.definitionId(), change.name(),
                    change.type(), change.stage(), change.mastery(), change.source(), change.description(),
                    change.rules(), change.maxUses(), change.remainingUses(), change.resetRule(),
                    absoluteDay(state), change.sideEffect()));
        } else if ("UPDATE".equals(change.operation()) && existing != null) {
            existing.stage = change.stage();
            existing.mastery = clamp(change.mastery(), 0, 100);
            existing.description = change.description();
            existing.rules = change.rules();
            existing.maxUses = change.maxUses();
            existing.remainingUses = change.remainingUses();
            existing.resetRule = change.resetRule();
            existing.lastResetDay = absoluteDay(state);
            existing.sideEffect = change.sideEffect();
        }
    }

    private void applyAbilityChanges(GameState state, java.util.List<AgentResult.AbilityChange> changes) {
        if (changes == null) return;
        changes.forEach(change -> applyAbilityChange(state, change));
    }

    public boolean refreshDailyAbilities(GameState state) {
        int day = absoluteDay(state);
        boolean[] changed = {false};
        state.abilities.forEach(ability -> {
            if ("DAILY".equals(ability.resetRule) && ability.lastResetDay < day) {
                ability.remainingUses = ability.maxUses;
                ability.lastResetDay = day;
                changed[0] = true;
            }
        });
        return changed[0];
    }

    private int absoluteDay(GameState state) {
        return state.worldYear * 360 + Math.max(0, state.day - 1);
    }

    private int absoluteTime(GameState state) {
        long value = (long) state.worldYear * 360L * 1440L + (long) Math.max(0, state.day - 1) * 1440L + state.minute;
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    private void applyCharacterPatch(GameState state, AgentResult.EntityPatch patch) {
        if (patch == null) return;
        state.characters.stream().filter(character -> character.id.equals(patch.id())).findFirst().ifPresent(character -> {
            patch.values().forEach((key, value) -> {
                switch (key) {
                    case "state" -> character.state = String.valueOf(value);
                    case "location" -> character.location = String.valueOf(value);
                    case "relation" -> character.relation = String.valueOf(value);
                    case "identity" -> character.identity = String.valueOf(value);
                    case "realm" -> character.realm = validCharacterRealm(String.valueOf(value));
                    case "condition" -> character.condition = validCondition(String.valueOf(value), character.condition);
                    case "combatProfile" -> character.combatProfile = String.valueOf(value);
                    default -> { }
                }
            });
            character.lastSeenAt = state.minute;
        });
    }

    private String validCharacterRealm(String value) {
        if (value == null) return "UNKNOWN";
        if ("UNKNOWN".equals(value)) return value;
        return java.util.List.of("凡俗", "引气", "筑基", "金丹", "元婴", "化神", "炼虚", "合道")
                .contains(value) ? value : "UNKNOWN";
    }

    private String validCondition(String value, String fallback) {
        if (value == null) return fallback;
        return java.util.Set.of("STABLE", "WOUNDED", "SEVERE", "DYING").contains(value)
                ? value : fallback;
    }

    private void applyCharacterChanges(GameState state, java.util.List<AgentResult.CharacterChange> changes) {
        if (changes == null) return;
        for (AgentResult.CharacterChange change : changes) {
            if (change == null) continue;
            GameState.CharacterRecord existing = state.characters.stream()
                    .filter(value -> change.id().equals(value.id)).findFirst().orElse(null);
            if (existing == null && "ADD".equals(change.operation())) {
                state.characters.add(new GameState.CharacterRecord(change.id(), change.name(), change.identity(),
                        validCharacterRealm(change.realm()), validCondition(change.condition(), "STABLE"),
                        change.combatProfile(), change.state(), change.location(), change.relation(), state.minute));
            } else if (existing != null) {
                existing.name = change.name();
                existing.identity = change.identity();
                existing.realm = validCharacterRealm(change.realm());
                existing.condition = validCondition(change.condition(), existing.condition);
                existing.combatProfile = change.combatProfile();
                existing.state = change.state();
                existing.location = change.location();
                existing.relation = change.relation();
                existing.lastSeenAt = state.minute;
            }
        }
    }

    private void applyThreadPatch(GameState state, AgentResult.EntityPatch patch) {
        if (patch == null) return;
        state.threads.stream().filter(thread -> thread.id.equals(patch.id())).findFirst().ifPresent(thread ->
                patch.values().forEach((key, value) -> {
                    switch (key) {
                        case "state" -> thread.state = String.valueOf(value);
                        case "danger" -> thread.danger = ((Number) value).intValue();
                        case "title" -> thread.title = String.valueOf(value);
                        default -> { }
                    }
                })
        );
    }

    private void applyThreadChanges(GameState state, java.util.List<AgentResult.EntityPatch> changes) {
        if (changes == null) return;
        changes.forEach(change -> applyThreadPatch(state, change));
    }

    private void applyLocationTransition(GameState state, AgentResult result) {
        LocationAuthority.repairLegacyState(state);
        AgentResult.LocationTransition transition = result.locationTransition();
        if (transition == null) {
            return;
        }
        state.currentLocation = transition.toLocationName();
        state.currentLocationId = transition.toLocationId();
        if (state.knownLocations.stream().noneMatch(location -> transition.toLocationId().equals(location.id))) {
            state.knownLocations.add(new GameState.LocationRecord(
                    transition.toLocationId(), transition.toLocationName(), transition.scope(),
                    transition.fromLocationId(), LocationAuthority.absoluteTime(state)));
        }
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void applyAdjudicatedCondition(GameState.PlayerState player, ConflictVerdict verdict) {
        if (player == null || verdict == null || verdict.playerConditionAfter() == null) return;
        setCondition(player, verdict.playerConditionAfter());
    }

    private void setCondition(GameState.PlayerState player, String condition) {
        if (condition == null) return;
        if (!java.util.Set.of("STABLE", "WOUNDED", "SEVERE", "DYING").contains(condition)) return;
        player.condition = condition;
        player.vitality = switch (condition) {
            case "WOUNDED" -> 35;
            case "SEVERE" -> 15;
            case "DYING" -> 5;
            default -> 60;
        };
    }
}

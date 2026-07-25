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
        refreshDailyAbilities(next);
        int absoluteMinute = next.minute + result.elapsed();
        next.day += absoluteMinute / 1440;
        next.minute = absoluteMinute % 1440;
        while (next.day > 360) {
            next.day -= 360;
            next.worldYear += 1;
            if (next.player != null) next.player.age += 1;
        }
        next.turn += 1;
        next.sceneId = result.sceneId();
        next.sceneTitle = result.sceneTitle() == null ? next.sceneTitle : result.sceneTitle();
        next.currentLocation = result.currentLocation() == null ? next.currentLocation : result.currentLocation();
        next.narrative = new ArrayList<>(result.narrative());
        next.signals = new ArrayList<>(result.signals());
        next.choices = result.choices() == null ? next.choices : new ArrayList<>(result.choices());

        applyPlayerPatch(next.player, result.playerPatch());
        applyCharacterPatch(next, result.characterPatch());
        applyThreadPatch(next, result.threadPatch());
        applyInventoryChange(next, result.inventoryChange());
        applyAbilityChange(next, result.abilityChange());

        AgentResult.EventDraft draft = result.event();
        next.events.add(0, new GameState.ChronicleEvent(
                "event-" + next.turn + "-" + UUID.randomUUID(),
                draft.title(), draft.detail(), absoluteTime(next), draft.kind()
        ));

        if ("DEAD".equals(result.outcome()) || next.player != null && next.player.vitality <= 0) {
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
                case "vitality" -> player.vitality = clamp(number, 0, 100);
                case "silver" -> player.silver = clamp(number, 0, 999_999);
                case "insight" -> player.insight = clamp(number, 0, 10);
                case "suspicion" -> player.suspicion = clamp(number, 0, 100);
                case "identity" -> player.identity = String.valueOf(value);
                case "goal" -> player.goal = String.valueOf(value);
                case "burden" -> player.burden = String.valueOf(value);
                case "realm" -> player.realm = validRealmChange(player.realm, String.valueOf(value));
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

    private void refreshDailyAbilities(GameState state) {
        int day = absoluteDay(state);
        state.abilities.forEach(ability -> {
            if ("DAILY".equals(ability.resetRule) && ability.lastResetDay < day) {
                ability.remainingUses = ability.maxUses;
                ability.lastResetDay = day;
            }
        });
    }

    private int absoluteDay(GameState state) {
        return state.worldYear * 360 + Math.max(0, state.day - 1);
    }

    private String validRealmChange(String current, String proposed) {
        var realms = java.util.List.of("凡俗", "引气", "筑基", "金丹", "元婴", "化神", "炼虚", "合道");
        int currentIndex = realms.indexOf(current);
        int proposedIndex = realms.indexOf(proposed);
        if (proposedIndex < 0 || currentIndex < 0 || proposedIndex > currentIndex + 1) return current;
        return proposed;
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
                    default -> { }
                }
            });
            character.lastSeenAt = state.minute;
        });
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

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}

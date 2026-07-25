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
        int absoluteMinute = next.minute + result.elapsed();
        next.day += absoluteMinute / 1440;
        next.minute = absoluteMinute % 1440;
        next.turn += 1;
        next.sceneId = result.sceneId();
        next.narrative = new ArrayList<>(result.narrative());
        next.signals = new ArrayList<>(result.signals());

        applyPlayerPatch(next.player, result.playerPatch());
        applyCharacterPatch(next, result.characterPatch());
        applyThreadPatch(next, result.threadPatch());

        AgentResult.EventDraft draft = result.event();
        next.events.add(0, new GameState.ChronicleEvent(
                "event-" + next.turn + "-" + UUID.randomUUID(),
                draft.title(), draft.detail(), next.minute, draft.kind()
        ));
        return next;
    }

    private void applyPlayerPatch(GameState.PlayerState player, Map<String, Object> patch) {
        if (patch == null) return;
        patch.forEach((key, value) -> {
            int number = value instanceof Number n ? n.intValue() : 0;
            switch (key) {
                case "vitality" -> player.vitality = number;
                case "silver" -> player.silver = number;
                case "insight" -> player.insight = number;
                case "suspicion" -> player.suspicion = number;
                case "name" -> player.name = String.valueOf(value);
                case "realm" -> player.realm = String.valueOf(value);
                case "constitution" -> player.constitution = String.valueOf(value);
                default -> { }
            }
        });
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
}

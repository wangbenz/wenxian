package com.wenxian.game.service;

import com.wenxian.game.model.ApiModels.TurnRequest;
import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ActionInputGateTest {
    private final ActionInputGate gate = new ActionInputGate();

    @Test
    void rejectsFillerAndRandomNumberWithoutAdvancingWorld() {
        GameState state = new GameState();
        assertThrows(ResponseStatusException.class, () -> gate.normalize(
                new TurnRequest("request-0001", 0, "TEXT", null, null, "哦哦"), state));
        assertThrows(ResponseStatusException.class, () -> gate.normalize(
                new TurnRequest("request-0002", 0, "TEXT", null, null, "12334535"), state));
    }
}

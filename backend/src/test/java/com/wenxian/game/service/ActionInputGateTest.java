package com.wenxian.game.service;

import com.wenxian.game.model.ApiModels.TurnRequest;
import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ActionInputGateTest {
    private final ActionInputGate gate = new ActionInputGate();

    @Test
    void rejectsFillerAndRandomNumberWithoutAdvancingWorld() {
        GameState state = new GameState();
        assertEquals("CLARIFY", gate.assess(
                new TurnRequest("request-0001", 0, "TEXT", null, null, "哦哦"), state).result());
        assertEquals("CLARIFY", gate.assess(
                new TurnRequest("request-0002", 0, "TEXT", null, null, "12334535"), state).result());
    }

    @Test
    void rejectsImpossibleRealmSkipWithUsefulSuggestions() {
        GameState state = new GameState();
        state.player = new GameState.PlayerState("宁临渊", "HUMAN", "MALE", 16, 75,
                "乞儿", "LONGEVITY", "求长生", "身无分文", "凡俗", "凡胎", 28, 0, 10, 72);

        ActionInputGate.Assessment result = gate.assess(
                new TurnRequest("request-0003", 0, "TEXT", null, null, "进阶元婴"), state);

        assertFalse(result.accepted());
        assertEquals("REJECTED", result.result());
        assertEquals(3, result.suggestions().size());
    }
}

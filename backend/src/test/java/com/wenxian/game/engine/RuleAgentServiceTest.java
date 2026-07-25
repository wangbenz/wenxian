package com.wenxian.game.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleAgentServiceTest {
    @Test
    void advancesAuthoritativeStateAndReturnsNextSceneChoices() {
        GameState initial = GameCatalog.initialState();
        RuleAgentService agent = new RuleAgentService();
        GameEngine engine = new GameEngine(new ObjectMapper().findAndRegisterModules());

        GameState next = engine.apply(initial, agent.resolve("ask-credit", "", initial));

        assertEquals("bargain", next.sceneId);
        assertEquals(1, next.turn);
        assertEquals(42, next.player.suspicion);
        assertEquals("以命赊药", next.events.getFirst().title);
        assertEquals("accept-job", GameCatalog.choicesFor(next.sceneId).getFirst().id());
    }
}

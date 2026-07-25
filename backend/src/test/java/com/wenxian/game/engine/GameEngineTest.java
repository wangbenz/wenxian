package com.wenxian.game.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameEngineTest {
    @Test
    void sealsTheLifeWhenVitalityReachesZero() {
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "mortal-fight";
        state.sceneTitle = "义庄";
        state.currentLocation = "城郊义庄";
        state.worldYear = 3001;
        state.day = 1;
        state.player = new GameState.PlayerState("陆沉", "HUMAN", "MALE", 16, 75,
                "守墓人", "LONGEVITY", "活下去", "旧债", "凡俗", "孱弱", 12, 3, 6, 40);

        AgentResult result = new AgentResult(
                "death", "义庄", "城郊义庄", 1, "CONTINUE",
                List.of("剑光掠过。", "你倒在尘土中。"), List.of("伤势致命"),
                List.of(new GameState.StoryChoice("impossible", "继续", "不应保留", "default")),
                Map.of("vitality", 0), new AgentResult.EventDraft("剑下亡魂", "你被一剑封喉。", "risk"),
                null, null, null, null,
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明")
        );

        GameState dead = new GameEngine(new ObjectMapper().findAndRegisterModules()).apply(state, result);

        assertEquals("dead", dead.phase);
        assertEquals(0, dead.player.vitality);
        assertTrue(dead.choices.isEmpty());
        assertNotNull(dead.death);
        assertEquals("剑下亡魂", dead.death.title);
        assertEquals(1, dead.death.turn);
    }
}

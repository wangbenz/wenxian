package com.wenxian.game.engine;

import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameCatalogTalentTest {
    @Test
    void selectingTalentMovesSaveIntoPlayingAndPersistsAbility() {
        GameState state = talentState();
        GameCatalog.enterWorld(state, "clear-sight");

        assertEquals("playing", state.phase);
        assertTrue(state.talentOptions.isEmpty());
        assertEquals("clear-sight", state.abilities.getFirst().id);
        assertEquals("gain", state.events.getFirst().kind);
    }

    @Test
    void skippingTalentStillMovesSaveIntoPlaying() {
        GameState state = talentState();
        GameCatalog.enterWorldWithoutTalent(state);

        assertEquals("playing", state.phase);
        assertTrue(state.abilities.isEmpty());
        assertEquals("凡骨入世", state.events.getFirst().title);
    }

    private GameState talentState() {
        GameState state = new GameState();
        state.phase = "talent";
        state.worldYear = 3001;
        state.day = 1;
        state.minute = 480;
        state.player = new GameState.PlayerState();
        state.player.name = "陆沉";
        state.abilities = new ArrayList<>();
        state.events = new ArrayList<>();
        state.talentOptions = new ArrayList<>();
        state.talentOptions.add(new GameState.AbilityRecord(
                "clear-sight", "clear-sight", "清明眼", "TALENT", "初醒", 1,
                "命格觉醒", "能短暂察觉细微异常。", "每日一次。", 1, 1, "DAILY", 1, "用后眼痛"
        ));
        return state;
    }
}

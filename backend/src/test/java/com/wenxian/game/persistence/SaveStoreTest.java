package com.wenxian.game.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.engine.GameEngine;
import com.wenxian.game.engine.RuleAgentService;
import com.wenxian.game.model.ApiModels.GameResponse;
import com.wenxian.game.service.GameService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SaveStoreTest {
    @TempDir
    Path tempDirectory;

    @Test
    void persistsAndReloadsAuthoritativeSnapshot() throws Exception {
        Path saveFile = tempDirectory.resolve("game-saves.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        GameEngine engine = new GameEngine(mapper);
        SaveStore firstStore = new SaveStore(mapper, saveFile.toString());
        firstStore.load();
        GameService firstService = new GameService(firstStore, new RuleAgentService(), engine);

        GameResponse advanced = firstService.act("persist-test-save", "ask-credit", "");
        assertEquals(1, advanced.revision());

        SaveStore restoredStore = new SaveStore(mapper, saveFile.toString());
        restoredStore.load();
        GameService restoredService = new GameService(restoredStore, new RuleAgentService(), engine);
        GameResponse restored = restoredService.get("persist-test-save");

        assertEquals("bargain", restored.state().sceneId);
        assertEquals(1, restored.state().turn);
        assertEquals(1, restored.revision());
    }
}

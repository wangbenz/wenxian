package com.wenxian.game.service;

import com.wenxian.game.engine.GameCatalog;
import com.wenxian.game.engine.GameEngine;
import com.wenxian.game.engine.RuleAgentService;
import com.wenxian.game.model.ApiModels.GameResponse;
import com.wenxian.game.model.GameState;
import com.wenxian.game.persistence.SaveStore;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class GameService {
    private final SaveStore saveStore;
    private final RuleAgentService agentService;
    private final GameEngine gameEngine;

    public GameService(SaveStore saveStore, RuleAgentService agentService, GameEngine gameEngine) {
        this.saveStore = saveStore;
        this.agentService = agentService;
        this.gameEngine = gameEngine;
    }

    public synchronized GameResponse get(String saveId) {
        SaveStore.StoredSave save = saveStore.find(saveId);
        if (save == null) save = saveStore.put(saveId, GameCatalog.initialState(), 0);
        return response(save);
    }

    public synchronized GameResponse act(String saveId, String actionId, String freeText) {
        SaveStore.StoredSave current = saveStore.find(saveId);
        if (current == null) current = saveStore.put(saveId, GameCatalog.initialState(), 0);
        GameState next = gameEngine.apply(current.state, agentService.resolve(actionId, freeText, current.state));
        return response(saveStore.put(saveId, next, current.revision + 1));
    }

    public synchronized GameResponse reset(String saveId) {
        return response(saveStore.put(saveId, GameCatalog.initialState(), 0));
    }

    private GameResponse response(SaveStore.StoredSave save) {
        return new GameResponse(
                gameEngine.copy(save.state),
                save.revision,
                save.updatedAt,
                GameCatalog.choicesFor(save.state.sceneId),
                Map.of("mode", "java-rule-agent-v1", "authoritative", true)
        );
    }
}

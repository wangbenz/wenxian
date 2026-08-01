package com.wenxian.game.engine;

import com.wenxian.game.config.RuntimeAiConfigService;
import com.wenxian.game.model.GameState;

import java.util.List;
import java.util.Map;

public interface NarrativeAgent {
    AgentTurn resolve(String action, GameState state, List<Map<String, Object>> history);
    default AgentTurn resolve(String action, GameState state, List<Map<String, Object>> history,
                              RuntimeAiConfigService.RuntimeConfig config, String credentialSource) {
        return resolve(action, state, history);
    }
    default AgentTurn resolve(String action, GameState state, List<Map<String, Object>> history,
                              RuntimeAiConfigService.RuntimeConfig config, String credentialSource,
                              ActionAdjudication adjudication) {
        return resolve(action, state, history, config, credentialSource);
    }
    String mode();
}

package com.wenxian.game.engine;

import com.wenxian.game.model.GameState;

import java.util.List;
import java.util.Map;

public interface NarrativeAgent {
    AgentTurn resolve(String action, GameState state, List<Map<String, Object>> history);
    String mode();
}

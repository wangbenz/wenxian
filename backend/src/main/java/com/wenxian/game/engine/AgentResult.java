package com.wenxian.game.engine;

import java.util.List;
import java.util.Map;

public record AgentResult(
        String sceneId,
        int elapsed,
        List<String> narrative,
        List<String> signals,
        Map<String, Object> playerPatch,
        EventDraft event,
        EntityPatch characterPatch,
        EntityPatch threadPatch
) {
    public record EventDraft(String title, String detail, String kind) {}
    public record EntityPatch(String id, Map<String, Object> values) {}
}

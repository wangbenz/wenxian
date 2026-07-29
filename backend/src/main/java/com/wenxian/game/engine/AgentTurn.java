package com.wenxian.game.engine;

public record AgentTurn(
        AgentResult result,
        String assistantMessage,
        String responseId,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String model,
        String credentialSource
) {
    public AgentTurn(AgentResult result, String assistantMessage, String responseId,
                     int promptTokens, int completionTokens, long latencyMs) {
        this(result, assistantMessage, responseId, promptTokens, completionTokens, latencyMs, "", "PUBLIC");
    }
}

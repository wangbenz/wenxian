package com.wenxian.game.engine;

public record AgentTurn(
        AgentResult result,
        String assistantMessage,
        String responseId,
        int promptTokens,
        int completionTokens,
        long latencyMs
) {}

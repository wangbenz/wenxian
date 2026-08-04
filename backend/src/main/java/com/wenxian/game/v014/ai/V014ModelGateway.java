package com.wenxian.game.v014.ai;

public interface V014ModelGateway {
    Result generate(Request request);

    record Request(String baseUrl, String apiKey, String provider, String adapterType, String model,
                   String systemPrompt, String userPrompt, double temperature) {}

    record Result(String text, String responseId, String model,
                  int inputTokens, int outputTokens, long latencyMs) {}
}

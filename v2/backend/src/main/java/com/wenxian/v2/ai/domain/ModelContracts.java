package com.wenxian.v2.ai.domain;

public final class ModelContracts {
    private ModelContracts() {
    }

    public record ModelRequest(
            String taskType,
            String userId,
            String saveId,
            String operationId,
            String systemPrompt,
            String userPrompt,
            int maxOutputTokens,
            double temperature
    ) {
    }

    public record ModelResult(
            String content,
            String providerCode,
            String modelCode,
            String providerRequestId,
            int promptTokens,
            int completionTokens,
            long latencyMillis
    ) {
    }
}

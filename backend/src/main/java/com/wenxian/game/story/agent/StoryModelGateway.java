package com.wenxian.game.story.agent;

/**
 * 故事领域访问大模型的版本无关网关。
 *
 * <p>业务层只提交模型路由和两段 Prompt，不依赖 DeepSeek、OpenAI 或某个产品发布版本的 SDK 类型。
 * 实现层负责关闭思考、JSON 响应、超时和用量读取；明文密钥不得离开一次调用的内存边界。</p>
 */
public interface StoryModelGateway {
    Result generate(Request request);

    record Request(
            String baseUrl,
            String apiKey,
            String provider,
            String adapterType,
            String model,
            String systemPrompt,
            String userPrompt,
            double temperature
    ) {}

    record Result(
            String text,
            String responseId,
            String model,
            int inputTokens,
            int outputTokens,
            long latencyMs
    ) {}
}

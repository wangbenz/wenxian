package com.wenxian.game.story.agent;

/**
 * 一次故事模型调用使用的内部路由。
 *
 * <p>它只在服务端内存中传递，不属于玩家 API。平台或玩家密钥由现有路由服务解析后临时注入，
 * Agent 不读取数据库，也不能自行替换供应商、模型或密钥来源。</p>
 */
public record StoryModelRoute(
        String baseUrl,
        String apiKey,
        String provider,
        String adapterType,
        String model,
        String credentialSource
) {
    public StoryModelRoute {
        require(baseUrl, "模型地址不能为空");
        require(apiKey, "模型密钥不能为空");
        require(provider, "模型供应商不能为空");
        require(adapterType, "模型适配器不能为空");
        require(model, "模型名称不能为空");
        require(credentialSource, "模型密钥来源不能为空");
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
    }
}

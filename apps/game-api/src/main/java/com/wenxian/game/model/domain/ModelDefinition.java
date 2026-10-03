package com.wenxian.game.model.domain;

import java.util.Set;

/** 不含密钥的供应商模型定义，可安全用于内部路由与观测标签。 */
public record ModelDefinition(
        String providerCode,
        String modelCode,
        String baseUrl,
        ModelAdapterType adapterType,
        String apiModelName,
        Set<ModelCapability> capabilities,
        int contextWindow,
        int maximumOutputTokens
) {
    public ModelDefinition {
        providerCode = required(providerCode, "供应商编码不能为空");
        modelCode = required(modelCode, "模型编码不能为空");
        baseUrl = required(baseUrl, "模型地址不能为空");
        adapterType = adapterType == null ? ModelAdapterType.OPENAI_COMPATIBLE : adapterType;
        apiModelName = required(apiModelName, "供应商模型名称不能为空");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        if (contextWindow <= 0) throw new IllegalArgumentException("上下文窗口必须大于零");
        if (maximumOutputTokens <= 0) throw new IllegalArgumentException("最大输出 Token 必须大于零");
    }

    public boolean supports(ModelTask task) {
        return capabilities.containsAll(task.requiredCapabilities());
    }

    public String identity() {
        return providerCode + ":" + modelCode;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.strip();
    }
}

package com.wenxian.game.model.domain;

/**
 * 模型已经通过平台验证的能力。
 *
 * <p>“接口兼容”不代表能力相同。路由只能依据这里显式登记的能力选择模型，
 * 不能根据供应商名称或模型名称猜测。</p>
 */
public enum ModelCapability {
    CHAT,
    JSON_MODE,
    JSON_SCHEMA,
    TOOLS,
    STREAMING,
    THINKING_CONTROL,
    CACHE_USAGE
}

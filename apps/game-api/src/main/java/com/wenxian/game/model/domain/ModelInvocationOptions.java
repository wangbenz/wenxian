package com.wenxian.game.model.domain;

import java.time.Duration;

/** 单个任务路由使用的生成参数；null 输出上限表示不主动发送 max_tokens，保留供应商默认值。 */
public record ModelInvocationOptions(
        double temperature,
        Duration timeout,
        int maximumRetries,
        Integer maximumOutputTokens,
        boolean disableThinking
) {
    public ModelInvocationOptions {
        if (temperature < 0 || temperature > 2) throw new IllegalArgumentException("温度必须在 0 到 2 之间");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("模型超时必须大于零");
        }
        if (maximumRetries < 0 || maximumRetries > 2) {
            throw new IllegalArgumentException("基础设施重试次数必须在 0 到 2 之间");
        }
        if (maximumOutputTokens != null && maximumOutputTokens <= 0) {
            throw new IllegalArgumentException("输出上限必须大于零");
        }
    }

    public static ModelInvocationOptions defaults() {
        return new ModelInvocationOptions(0.7, Duration.ofSeconds(90), 0, null, true);
    }
}

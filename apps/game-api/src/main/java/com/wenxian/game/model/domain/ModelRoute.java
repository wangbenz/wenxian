package com.wenxian.game.model.domain;

import java.util.Optional;

/** 已验证的主模型与至多一个备用模型。 */
public record ModelRoute(
        ModelTask task,
        String requestId,
        ModelRouteCandidate primary,
        ModelRouteCandidate fallback
) {
    public ModelRoute {
        if (task == null) throw new IllegalArgumentException("模型任务不能为空");
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("请求编号不能为空");
        if (primary == null) throw new IllegalArgumentException("主模型不能为空");
    }

    public Optional<ModelRouteCandidate> fallbackCandidate() {
        return Optional.ofNullable(fallback);
    }
}

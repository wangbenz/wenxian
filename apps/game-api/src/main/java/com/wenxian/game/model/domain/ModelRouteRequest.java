package com.wenxian.game.model.domain;

/** 一次业务任务的路由请求。请求编号用于保证降级仍属于同一玩家行动。 */
public record ModelRouteRequest(
        ModelTask task,
        String requestId,
        String userId,
        boolean allowPlatformFallback,
        ServicePlanSelection servicePlanSelection
) {
    public ModelRouteRequest(ModelTask task, String requestId, String userId, boolean allowPlatformFallback) {
        this(task, requestId, userId, allowPlatformFallback, null);
    }

    public ModelRouteRequest {
        if (task == null) throw new IllegalArgumentException("模型任务不能为空");
        requestId = required(requestId, "请求编号不能为空");
        userId = required(userId, "用户编号不能为空");
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.strip();
    }
}

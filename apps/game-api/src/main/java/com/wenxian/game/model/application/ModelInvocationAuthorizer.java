package com.wenxian.game.model.application;

import com.wenxian.game.model.domain.ModelRoute;
import com.wenxian.game.model.domain.ModelRouteRequest;

/** 模型任务在真实请求发出前的访问授权端口。 */
public interface ModelInvocationAuthorizer {
    void authorize(ModelRouteRequest request, ModelRoute route);
}

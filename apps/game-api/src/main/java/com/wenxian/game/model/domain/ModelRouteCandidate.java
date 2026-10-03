package com.wenxian.game.model.domain;

/** 路由中的一个候选模型，凭据只在进程内传递。 */
public record ModelRouteCandidate(
        ModelDefinition model,
        ModelCredential credential,
        ModelInvocationOptions options
) {
    public ModelRouteCandidate {
        if (model == null) throw new IllegalArgumentException("候选模型不能为空");
        if (credential == null) throw new IllegalArgumentException("候选密钥不能为空");
        options = options == null ? ModelInvocationOptions.defaults() : options;
    }
}

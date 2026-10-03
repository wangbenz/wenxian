package com.wenxian.game.model.application;

import com.wenxian.game.model.domain.ModelRouteCandidate;
import com.wenxian.game.model.domain.ModelRouteRequest;
import com.wenxian.game.model.domain.CredentialSource;

import java.util.List;

/** 从管理配置、平台模型和玩家偏好中读取候选；本接口不负责执行调用。 */
public interface ModelRoutePolicy {
    ResolvedCandidates resolve(ModelRouteRequest request);

    record ResolvedCandidates(CredentialSource credentialSource, List<ModelRouteCandidate> candidates) {
        public ResolvedCandidates {
            if (credentialSource == null) throw new IllegalArgumentException("已解析的密钥来源不能为空");
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }
}

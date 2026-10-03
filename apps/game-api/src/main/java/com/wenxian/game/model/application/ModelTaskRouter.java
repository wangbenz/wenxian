package com.wenxian.game.model.application;

import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelRoute;
import com.wenxian.game.model.domain.ModelRouteCandidate;
import com.wenxian.game.model.domain.ModelRouteRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 对任务路由做统一能力和降级边界校验。
 *
 * <p>路由器只选择模型，不扣回合、不调用模型、不写故事事实。</p>
 */
@Component
public final class ModelTaskRouter {
    private final ModelRoutePolicy policy;

    public ModelTaskRouter(ModelRoutePolicy policy) {
        if (policy == null) throw new IllegalArgumentException("模型路由策略不能为空");
        this.policy = policy;
    }

    public ModelRoute route(ModelRouteRequest request) {
        ModelRoutePolicy.ResolvedCandidates resolved = policy.resolve(request);
        CredentialSource selectedSource = resolved.credentialSource();
        List<ModelRouteCandidate> candidates = new ArrayList<>(safe(resolved.candidates()));
        if (candidates.isEmpty()) throw new ModelRoutingException("MODEL_ROUTE_UNAVAILABLE", "当前任务没有可用模型");
        if (candidates.size() > 2) {
            throw new ModelRoutingException("MODEL_ROUTE_TOO_MANY_CANDIDATES", "每次任务只允许一个主模型和一个备用模型");
        }
        candidates.forEach(candidate -> validateCapabilities(request, candidate));
        ModelRouteCandidate primary = candidates.getFirst();
        if (primary.credential().source() != selectedSource) {
            throw new ModelRoutingException("MODEL_CREDENTIAL_SOURCE_MISMATCH", "主模型密钥来源与玩家选择不一致");
        }
        ModelRouteCandidate fallback = candidates.size() == 2 ? candidates.get(1) : null;
        if (selectedSource == CredentialSource.PLATFORM && fallback != null
                && fallback.credential().source() != CredentialSource.PLATFORM) {
            throw new ModelRoutingException("MODEL_CREDENTIAL_SOURCE_MISMATCH", "平台模式不能使用玩家密钥作为备用模型");
        }
        if (selectedSource == CredentialSource.USER && fallback != null
                && fallback.credential().source() == CredentialSource.PLATFORM
                && !request.allowPlatformFallback()) {
            throw new ModelRoutingException("PLATFORM_FALLBACK_NOT_ALLOWED", "玩家自备密钥失败时不能自动消费平台推演点");
        }
        if (fallback != null && primary.model().equals(fallback.model())
                && primary.credential().fingerprint().equals(fallback.credential().fingerprint())
                && primary.options().equals(fallback.options())) {
            throw new ModelRoutingException("MODEL_FALLBACK_DUPLICATED", "备用模型不能与主模型完全相同");
        }
        return new ModelRoute(request.task(), request.requestId(), primary, fallback);
    }

    private void validateCapabilities(ModelRouteRequest request, ModelRouteCandidate candidate) {
        Set<ModelCapability> missing = new java.util.HashSet<>(request.task().requiredCapabilities());
        missing.removeAll(candidate.model().capabilities());
        if (!missing.isEmpty()) {
            throw new ModelRoutingException("MODEL_CAPABILITY_MISSING",
                    candidate.model().identity() + " 缺少任务能力：" + missing);
        }
        if (candidate.options().disableThinking()
                && !candidate.model().capabilities().contains(ModelCapability.THINKING_CONTROL)) {
            throw new ModelRoutingException("THINKING_CONTROL_UNSUPPORTED",
                    candidate.model().identity() + " 不支持关闭思考模式");
        }
    }

    private List<ModelRouteCandidate> safe(List<ModelRouteCandidate> candidates) {
        return candidates == null ? List.of() : candidates.stream().filter(java.util.Objects::nonNull).toList();
    }

    public static final class ModelRoutingException extends IllegalStateException {
        private final String code;

        public ModelRoutingException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

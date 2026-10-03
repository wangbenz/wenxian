package com.wenxian.game.model.application;

import com.wenxian.game.config.SecretCipher;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import com.wenxian.game.model.domain.ModelRouteCandidate;
import com.wenxian.game.model.domain.ModelRouteRequest;
import com.wenxian.game.model.persistence.ModelCatalogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 从中性模型表解析平台或玩家自备模型路由。
 *
 * <p>该策略以账号偏好决定密钥来源，在路由边界完成解密；不调用模型、不扣回合、不写故事事实。
 * 玩家自备模式缺少有效密钥时直接失败，不静默消耗平台额度。中性模型目录是唯一运行时来源。</p>
 */
@Component
public class JdbcModelRoutePolicy implements ModelRoutePolicy {
    private final ModelCatalogRepository catalog;
    private final ServicePlanRuntimeService servicePlans;
    private final String masterKey;

    @Autowired
    public JdbcModelRoutePolicy(ModelCatalogRepository catalog, ServicePlanRuntimeService servicePlans,
                                @Value("${game.ai-config-master-key:wenxian-local-config-key-change-me}")
                                String masterKey) {
        this.catalog = catalog;
        this.servicePlans = servicePlans;
        this.masterKey = masterKey;
    }

    /** 仅供不加载三档方案的单元测试与离线装配使用。 */
    JdbcModelRoutePolicy(ModelCatalogRepository catalog, String masterKey) {
        this.catalog = catalog;
        this.servicePlans = null;
        this.masterKey = masterKey;
    }

    @Override
    public ResolvedCandidates resolve(ModelRouteRequest request) {
        ModelCatalogRepository.UserPreference preference = catalog.findUserPreference(request.userId()).orElse(null);
        if (preference != null && "USER".equalsIgnoreCase(preference.credentialSource())) {
            return personal(request, preference);
        }
        return platform(request);
    }

    private ResolvedCandidates personal(ModelRouteRequest request,
                                        ModelCatalogRepository.UserPreference preference) {
        ModelDefinition model = catalog.findUserSelectableModel(preference.providerCode(), preference.modelCode())
                .orElseThrow(() -> unavailable("玩家选择的模型未开放"));
        String ciphertext = catalog.findActiveUserCredential(request.userId(), preference.providerCode())
                .map(ModelCatalogRepository.EncryptedCredential::ciphertext)
                .orElseThrow(() -> unavailable("玩家自备密钥不存在或未通过验证"));
        ModelInvocationOptions options = catalog.findTaskRoute(request.task())
                .map(ModelCatalogRepository.TaskRoute::options)
                .orElseGet(ModelInvocationOptions::defaults);
        return new ResolvedCandidates(CredentialSource.USER, List.of(new ModelRouteCandidate(model,
                ModelCredential.user(decrypt(ciphertext)), options)));
    }

    private ResolvedCandidates platform(ModelRouteRequest request) {
        ModelCatalogRepository.TaskRoute configured = request.servicePlanSelection() == null
                ? catalog.findTaskRoute(request.task())
                    .orElseThrow(() -> unavailable("平台任务路由尚未配置"))
                : requireServicePlanRoute(request);
        ModelInvocationOptions options = configured.options();

        List<ModelRouteCandidate> candidates = new ArrayList<>();
        candidates.add(platformCandidate(configured.primaryProviderCode(), configured.primaryModelCode(), options));
        if (configured.fallbackEnabled()
                && configured.fallbackProviderCode() != null && configured.fallbackModelCode() != null) {
            candidates.add(platformCandidate(configured.fallbackProviderCode(), configured.fallbackModelCode(),
                    options));
        }
        return new ResolvedCandidates(CredentialSource.PLATFORM, candidates);
    }

    private ModelCatalogRepository.TaskRoute requireServicePlanRoute(ModelRouteRequest request) {
        if (servicePlans == null) throw unavailable("三档推演服务尚未装配");
        return servicePlans.requireTaskRoute(request.servicePlanSelection(), request.task());
    }

    private ModelRouteCandidate platformCandidate(String provider, String modelCode,
                                                  ModelInvocationOptions options) {
        ModelDefinition model = catalog.findPlatformModel(provider, modelCode)
                .orElseThrow(() -> unavailable("平台模型路由未开放"));
        String ciphertext = catalog.findPlatformCredential(provider)
                .map(ModelCatalogRepository.EncryptedCredential::ciphertext)
                .orElseThrow(() -> unavailable("平台模型密钥尚未配置"));
        String secret = decrypt(ciphertext);
        return new ModelRouteCandidate(model, ModelCredential.platform(secret), options);
    }

    private String decrypt(String ciphertext) {
        try {
            return SecretCipher.decrypt(ciphertext, masterKey);
        } catch (RuntimeException error) {
            throw new ModelTaskRouter.ModelRoutingException("MODEL_CREDENTIAL_DECRYPT_FAILED", "模型密钥无法读取");
        }
    }

    private ModelTaskRouter.ModelRoutingException unavailable(String message) {
        return new ModelTaskRouter.ModelRoutingException("MODEL_ROUTE_UNAVAILABLE", message);
    }
}

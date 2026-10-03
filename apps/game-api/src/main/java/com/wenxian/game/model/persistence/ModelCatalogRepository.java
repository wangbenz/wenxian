package com.wenxian.game.model.persistence;

import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import com.wenxian.game.model.domain.ModelTask;

import java.util.Optional;

/**
 * 中性模型目录、任务路由和加密凭据的只读仓储契约。
 *
 * <p>模型配置表是路由事实来源；仓储只返回密文，不负责解密、调用模型、扣除额度或写入故事事务。
 * 缺少记录以 {@link Optional#empty()} 表达，由上层统一转换为安全错误。</p>
 */
public interface ModelCatalogRepository {
    Optional<ModelDefinition> findPlatformModel(String providerCode, String modelCode);

    Optional<ModelDefinition> findUserSelectableModel(String providerCode, String modelCode);

    Optional<TaskRoute> findTaskRoute(ModelTask task);

    Optional<UserPreference> findUserPreference(String userId);

    Optional<EncryptedCredential> findPlatformCredential(String providerCode);

    Optional<EncryptedCredential> findActiveUserCredential(String userId, String providerCode);

    record TaskRoute(
            ModelTask task,
            String primaryProviderCode,
            String primaryModelCode,
            String fallbackProviderCode,
            String fallbackModelCode,
            ModelInvocationOptions options,
            boolean fallbackEnabled
    ) {}

    record UserPreference(String providerCode, String modelCode, String credentialSource) {}

    record EncryptedCredential(String ciphertext) {}
}

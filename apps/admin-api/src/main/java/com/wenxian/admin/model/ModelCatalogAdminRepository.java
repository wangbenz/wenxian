package com.wenxian.admin.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 模型库管理用例的持久化边界。
 *
 * <p>仓储只表达查询和写入，不判断模型能否停用、能否切换路由，也不解密平台密钥。
 * 运行时仍由 game-api 的模型目录仓储读取同一组权威表。</p>
 */
public interface ModelCatalogAdminRepository {

    int countPlatformTasks();

    List<ProviderRecord> findProviders();

    List<ModelRecord> findModels();

    List<RouteReference> findEffectiveRouteReferences();

    List<ServicePlanReference> findServicePlanReferences();

    List<ValidationRecord> findLatestValidations();

    Optional<ProviderCredentialRecord> findProviderCredential(String providerCode);

    boolean providerExists(String providerCode);

    int updateProvider(String providerCode, String displayName, String baseUrl, String ciphertext,
                       boolean replaceCiphertext, boolean enabled, boolean userKeyEnabled, Instant updatedAt);

    void insertProvider(String providerCode, String displayName, String baseUrl, String ciphertext,
                        boolean enabled, boolean userKeyEnabled, Instant updatedAt);

    int updateModel(String providerCode, String modelCode, String displayName, String apiModelName,
                    String capabilityCodes, int contextWindow, int maximumOutputTokens,
                    int concurrencyLimit, boolean platformEnabled, boolean userSelectable, Instant updatedAt);

    void insertModel(String providerCode, String modelCode, String displayName, String apiModelName,
                     String capabilityCodes, int contextWindow, int maximumOutputTokens,
                     int concurrencyLimit, boolean platformEnabled, boolean userSelectable, Instant updatedAt);

    int routeAllPlatformTasks(String providerCode, String modelCode, Instant updatedAt);

    record ProviderRecord(String providerCode, String displayName, String baseUrl, String adapterType,
                          String platformSecretCiphertext, boolean enabled, boolean userKeyEnabled,
                          long configVersion, Instant updatedAt) { }

    record ModelRecord(String providerCode, String modelCode, String displayName, String apiModelName,
                       String capabilityCodes, int contextWindow, int maximumOutputTokens,
                       int concurrencyLimit, boolean platformEnabled, boolean userSelectable,
                       long configVersion, Instant updatedAt) { }

    record RouteReference(String taskCode, String providerCode, String modelCode, boolean primary) { }

    /** 草稿或生效方案中的引用；即使回退暂未启用也需先从草稿移除后才能停用模型。 */
    record ServicePlanReference(String planId, long versionNumber, String status, String tierCode,
                                String taskCode, String providerCode, String modelCode, String usage) { }

    record ValidationRecord(String id, String providerCode, String modelCode, long providerConfigVersion,
                            long modelConfigVersion, String status, String capabilityCodes,
                            Integer latencyMs, String errorCode, String errorSummary, Instant checkedAt) { }

    record ProviderCredentialRecord(String ciphertext, boolean enabled) { }
}

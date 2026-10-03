package com.wenxian.game.story.infrastructure;

import com.wenxian.game.account.AccountActivityService;
import com.wenxian.game.account.AccountActivitySource;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.config.SecretCipher;
import com.wenxian.game.credit.PlatformTurnQuotaService;
import com.wenxian.game.model.application.ProviderCredentialVerifier;
import com.wenxian.game.model.application.ServicePlanRuntimeService;
import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelTask;
import com.wenxian.game.model.infrastructure.ChatModelRegistry;
import com.wenxian.game.model.persistence.ModelCatalogRepository;
import com.wenxian.game.story.agent.StoryModelSelectionGateway;
import com.wenxian.game.story.application.GenerationSettingsGateway;
import com.wenxian.game.story.model.GenerationApiModels.CredentialRequest;
import com.wenxian.game.story.model.GenerationApiModels.PersonalModel;
import com.wenxian.game.story.model.GenerationApiModels.PersonalProvider;
import com.wenxian.game.story.model.GenerationApiModels.PlatformAccess;
import com.wenxian.game.story.model.GenerationApiModels.PlatformTier;
import com.wenxian.game.story.model.GenerationApiModels.SettingsRequest;
import com.wenxian.game.story.model.GenerationApiModels.SettingsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 基于统一模型目录、偏好和凭据表实现玩家推演设置。
 *
 * <p>本实现同时承担故事回合所需的脱敏选择查询，但不解析任务密钥；真实路由仍由
 * {@code ModelTaskRouter} 冻结。稳定设置链只写 {@code user_model_preference}，不再双写历史版本表。</p>
 */
@Component
public class JdbcGenerationSettingsGateway implements GenerationSettingsGateway, StoryModelSelectionGateway {
    private static final String DEFAULT_PROVIDER = "DEEPSEEK";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    private final JdbcTemplate jdbc;
    private final PlatformTurnQuotaService quota;
    private final ModelCatalogRepository catalog;
    private final ProviderCredentialVerifier verifier;
    private final ChatModelRegistry modelRegistry;
    private final ServicePlanRuntimeService servicePlans;
    private final String masterKey;
    private final AccountActivityService accountActivity;

    @Autowired
    public JdbcGenerationSettingsGateway(JdbcTemplate jdbc, PlatformTurnQuotaService quota,
                                         ModelCatalogRepository catalog, ProviderCredentialVerifier verifier,
                                         ChatModelRegistry modelRegistry, ServicePlanRuntimeService servicePlans,
                                         @Value("${game.ai-config-master-key:wenxian-local-config-key-change-me}")
                                         String masterKey,
                                         AccountActivityService accountActivity) {
        this.jdbc = jdbc;
        this.quota = quota;
        this.catalog = catalog;
        this.verifier = verifier;
        this.modelRegistry = modelRegistry;
        this.servicePlans = servicePlans;
        this.masterKey = masterKey;
        this.accountActivity = accountActivity;
    }

    JdbcGenerationSettingsGateway(JdbcTemplate jdbc, PlatformTurnQuotaService quota,
                                  ModelCatalogRepository catalog, ProviderCredentialVerifier verifier,
                                  ChatModelRegistry modelRegistry, ServicePlanRuntimeService servicePlans,
                                  String masterKey) {
        this(jdbc, quota, catalog, verifier, modelRegistry, servicePlans, masterKey, null);
    }

    /** 仅供不加载三档方案的设置单元测试使用。 */
    JdbcGenerationSettingsGateway(JdbcTemplate jdbc, PlatformTurnQuotaService quota,
                                  ModelCatalogRepository catalog, ProviderCredentialVerifier verifier,
                                  ChatModelRegistry modelRegistry, String masterKey) {
        this(jdbc, quota, catalog, verifier, modelRegistry, null, masterKey, null);
    }

    @Override
    public SettingsResponse settings(String userId) {
        Preference preference = preference(userId);
        PlatformTurnQuotaService.Settings quotaSettings = quota.settings(userId);
        List<PersonalProvider> providers = jdbc.query("""
                select provider_code, display_name from ai_provider_config
                where enabled=true and user_key_enabled=true order by display_name, provider_code
                """, (row, index) -> personalProvider(userId, row.getString(1), row.getString(2)));
        boolean personal = preference != null && "USER".equals(preference.credentialSource());
        List<PlatformTier> tiers = servicePlans == null ? List.of() : servicePlans.playerTiers(userId).stream()
                .map(tier -> new PlatformTier(tier.code(), tier.name(), tier.description(),
                        tier.pointCost(), tier.selected()))
                .toList();
        String selectedTier = tiers.stream().filter(PlatformTier::selected)
                .map(PlatformTier::code).findFirst().orElse(null);
        return new SettingsResponse(personal ? "PERSONAL" : "PLATFORM",
                new PlatformAccess(quotaSettings.turnLimit(), quotaSettings.usedTurns(),
                        quotaSettings.remainingTurns(), tiers.isEmpty() ? platformAvailable() : true),
                tiers, selectedTier,
                providers, personal ? preference.provider() : null, personal ? preference.model() : null);
    }

    @Override
    @Transactional
    public SettingsResponse saveSettings(String userId, SettingsRequest request) {
        if ("PLATFORM".equals(request.mode())) {
            if (request.tierCode() != null && !request.tierCode().isBlank()) {
                if (servicePlans == null) throw GameApiException.of(HttpStatus.CONFLICT,
                        "SERVICE_TIER_UNAVAILABLE", "MODEL", "推演档位暂不可用",
                        "服务器尚未启用三档推演服务。", List.of("稍后重试"));
                try {
                    servicePlans.selectTier(userId, request.tierCode());
                } catch (RuntimeException unavailable) {
                    throw GameApiException.of(HttpStatus.CONFLICT, "SERVICE_TIER_UNAVAILABLE", "MODEL",
                            "推演档位暂不可用", "所选档位不在当前生效方案中。",
                            List.of("刷新设置后重新选择"));
                }
            }
            Preference previous = preference(userId);
            Preference fallback = previous == null ? defaultPreference() : previous;
            upsertPreference(userId, fallback.provider(), fallback.model(), "PUBLIC");
            return settings(userId);
        }
        ModelDefinition selected = requirePersonalModel(request.provider(), request.model());
        Credential credential = credential(userId, selected.providerCode());
        if (credential == null || !"ACTIVE".equals(credential.status())) {
            throw GameApiException.of(HttpStatus.CONFLICT, "PERSONAL_KEY_REQUIRED", "CREDENTIAL",
                    "请先验证自己的推演密钥", "所选推演服务还没有可用密钥，不能切换为自备模式。",
                    List.of("验证并保存密钥后再启用"));
        }
        upsertPreference(userId, selected.providerCode(), selected.modelCode(), "USER");
        return settings(userId);
    }

    @Override
    @Transactional
    public SettingsResponse saveCredential(String userId, String provider, CredentialRequest request) {
        String apiKey = request.apiKey() == null ? "" : request.apiKey().strip();
        validateApiKey(apiKey);
        ModelDefinition selected = request.model() == null || request.model().isBlank()
                ? firstPersonalModel(provider) : requirePersonalModel(provider, request.model());
        verifier.verify(selected, apiKey);
        invalidateCachedCredential(credential(userId, selected.providerCode()));

        Instant now = Instant.now();
        String ciphertext = SecretCipher.encrypt(apiKey, masterKey);
        String suffix = apiKey.substring(Math.max(0, apiKey.length() - 4));
        int changed = jdbc.update("""
                update user_ai_provider_credential
                set api_key_ciphertext=?, masked_suffix=?, status='ACTIVE', last_verified_at=?,
                    last_error_code=null, updated_at=? where user_id=? and provider_code=?
                """, ciphertext, suffix, Timestamp.from(now), Timestamp.from(now), userId,
                selected.providerCode());
        if (changed == 0) {
            jdbc.update("""
                    insert into user_ai_provider_credential(user_id, provider_code, api_key_ciphertext,
                        masked_suffix, status, last_verified_at, last_error_code, created_at, updated_at)
                    values (?, ?, ?, ?, 'ACTIVE', ?, null, ?, ?)
                    """, userId, selected.providerCode(), ciphertext, suffix, Timestamp.from(now),
                    Timestamp.from(now), Timestamp.from(now));
        }
        upsertPreference(userId, selected.providerCode(), selected.modelCode(), "USER");
        if (accountActivity != null) {
            accountActivity.record(userId, AccountActivitySource.BYOK_CHANGED, now);
        }
        return settings(userId);
    }

    @Override
    @Transactional
    public SettingsResponse deleteCredential(String userId, String provider) {
        String normalized = normalize(provider);
        invalidateCachedCredential(credential(userId, normalized));
        jdbc.update("delete from user_ai_provider_credential where user_id=? and provider_code=?",
                userId, normalized);
        Preference selected = preference(userId);
        if (selected != null && normalized.equals(selected.provider())
                && "USER".equals(selected.credentialSource())) {
            upsertPreference(userId, selected.provider(), selected.model(), "PUBLIC");
        }
        if (accountActivity != null) {
            accountActivity.record(userId, AccountActivitySource.BYOK_DELETED, Instant.now());
        }
        return settings(userId);
    }

    @Override
    public StoryModelSelection currentSelection(String userId) {
        Preference stored = preference(userId);
        Preference selected = stored == null ? defaultPreference() : stored;
        return new StoryModelSelection(selected.provider(), selected.model(), selected.credentialSource());
    }

    @Override
    @Transactional
    public void markCredentialInvalid(String userId, String provider, String reason) {
        String normalized = normalize(provider);
        invalidateCachedCredential(credential(userId, normalized));
        jdbc.update("""
                update user_ai_provider_credential
                set status='INVALID', last_error_code=?, updated_at=?
                where user_id=? and provider_code=?
                """, reason, Timestamp.from(Instant.now()), userId, normalized);
    }

    private PersonalProvider personalProvider(String userId, String provider, String displayName) {
        Credential credential = credential(userId, provider);
        List<PersonalModel> models = jdbc.query("""
                select model_code, display_name from ai_provider_model
                where provider_code=? and user_selectable=true order by display_name, model_code
                """, (row, index) -> new PersonalModel(row.getString(1), row.getString(2)), provider);
        return new PersonalProvider(provider, displayName, credential != null,
                credential == null ? null : credential.status(),
                credential == null ? null : credential.maskedSuffix(),
                credential == null ? null : credential.lastVerifiedAt(), models);
    }

    private boolean platformAvailable() {
        try {
            var route = catalog.findTaskRoute(ModelTask.TURN_DIRECTOR).orElse(null);
            if (route == null) return false;
            String provider = route.primaryProviderCode();
            String model = route.primaryModelCode();
            if (catalog.findPlatformModel(provider, model).isEmpty()) return false;
            return catalog.findPlatformCredential(provider).isPresent();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private Preference defaultPreference() {
        var route = catalog.findTaskRoute(ModelTask.TURN_DIRECTOR).orElse(null);
        return route == null
                ? new Preference(DEFAULT_PROVIDER, DEFAULT_MODEL, "PUBLIC")
                : new Preference(route.primaryProviderCode(), route.primaryModelCode(), "PUBLIC");
    }

    private ModelDefinition firstPersonalModel(String provider) {
        String normalized = normalize(provider);
        String model = jdbc.query("""
                select model_code from ai_provider_model
                where provider_code=? and user_selectable=true order by display_name, model_code limit 1
                """, (row, index) -> row.getString(1), normalized).stream().findFirst()
                .orElseThrow(() -> unsupportedModel(normalized));
        return requirePersonalModel(normalized, model);
    }

    private ModelDefinition requirePersonalModel(String provider, String model) {
        String normalized = normalize(provider);
        if (model == null || model.isBlank()) throw unsupportedModel(normalized);
        return catalog.findUserSelectableModel(normalized, model).orElseThrow(() -> unsupportedModel(normalized));
    }

    private Preference preference(String userId) {
        return jdbc.query("""
                select provider_code, model_code, credential_source from user_model_preference where user_id=?
                """, (row, index) -> new Preference(row.getString(1), row.getString(2), row.getString(3)), userId)
                .stream().findFirst().orElse(null);
    }

    private Credential credential(String userId, String provider) {
        return jdbc.query("""
                select api_key_ciphertext, masked_suffix, status, last_verified_at
                from user_ai_provider_credential where user_id=? and provider_code=?
                """, (row, index) -> new Credential(row.getString(1), row.getString(2), row.getString(3),
                row.getTimestamp(4) == null ? null : row.getTimestamp(4).toInstant()), userId, normalize(provider))
                .stream().findFirst().orElse(null);
    }

    private void upsertPreference(String userId, String provider, String model, String source) {
        Instant now = Instant.now();
        int changed = jdbc.update("""
                update user_model_preference set provider_code=?, model_code=?, credential_source=?, updated_at=?
                where user_id=?
                """, normalize(provider), model, source, Timestamp.from(now), userId);
        if (changed == 0) {
            jdbc.update("""
                    insert into user_model_preference(user_id, provider_code, model_code, credential_source, updated_at)
                    values (?, ?, ?, ?, ?)
                    """, userId, normalize(provider), model, source, Timestamp.from(now));
        }
    }

    private void invalidateCachedCredential(Credential credential) {
        if (credential == null || credential.ciphertext() == null || credential.ciphertext().isBlank()) return;
        try {
            modelRegistry.invalidateCredential(ModelCredential.user(
                    SecretCipher.decrypt(credential.ciphertext(), masterKey)));
        } catch (RuntimeException ignored) {
            // 无法解密的旧密钥也必须允许被覆盖、删除或标记失效；注册表本身有数量上限。
        }
    }

    private void validateApiKey(String apiKey) {
        if (apiKey.length() < 20 || apiKey.length() > 512
                || apiKey.chars().anyMatch(Character::isWhitespace)) {
            throw GameApiException.of(HttpStatus.BAD_REQUEST, "PERSONAL_KEY_FORMAT_INVALID", "CREDENTIAL",
                    "推演密钥格式不正确", "请粘贴完整密钥，内容中不能包含空格。",
                    List.of("重新复制完整密钥后验证"));
        }
    }

    private GameApiException unsupportedModel(String provider) {
        return GameApiException.of(HttpStatus.BAD_REQUEST, "PERSONAL_MODEL_UNAVAILABLE", "CREDENTIAL",
                "所选推演方式暂不可用", "当前没有开放这个供应商或模型。", List.of("重新选择可用模型"));
    }

    private String normalize(String provider) {
        return provider == null ? "" : provider.strip().toUpperCase(Locale.ROOT);
    }

    private record Preference(String provider, String model, String credentialSource) {}

    private record Credential(String ciphertext, String maskedSuffix, String status, Instant lastVerifiedAt) {}
}

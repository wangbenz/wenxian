package com.wenxian.game.v014.service;

import com.wenxian.game.api.GameApiException;
import com.wenxian.game.config.RuntimeAiConfigService;
import com.wenxian.game.config.SecretCipher;
import com.wenxian.game.service.PlayerAiService;
import com.wenxian.game.v014.ai.V014ProviderCredentialVerifier;
import com.wenxian.game.v014.model.V014ApiModels.GenerationSettings;
import com.wenxian.game.v014.model.V014ApiModels.ModelPreference;
import com.wenxian.game.v014.model.V014ApiModels.PersonalModel;
import com.wenxian.game.v014.model.V014ApiModels.PersonalProvider;
import com.wenxian.game.v014.model.V014ApiModels.PlatformAccess;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class V014ModelPreferenceService {
    private static final String PLATFORM_PROVIDER_KEY = "v014_platform_provider";
    private static final String PLATFORM_MODEL_KEY = "v014_platform_model";
    private static final String DEFAULT_PROVIDER = "DEEPSEEK";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    private final JdbcTemplate jdbc;
    private final PlayerAiService playerAi;
    private final RuntimeAiConfigService runtimeConfig;
    private final V014ProviderCredentialVerifier verifier;
    private final String masterKey;
    private final String defaultBaseUrl;

    @Autowired
    public V014ModelPreferenceService(JdbcTemplate jdbc, PlayerAiService playerAi,
                                      RuntimeAiConfigService runtimeConfig,
                                      V014ProviderCredentialVerifier verifier,
                                      @Value("${game.ai-config-master-key:wenxian-local-config-key-change-me}") String masterKey,
                                      @Value("${deepseek.base-url:https://api.deepseek.com}") String defaultBaseUrl) {
        this.jdbc = jdbc;
        this.playerAi = playerAi;
        this.runtimeConfig = runtimeConfig;
        this.verifier = verifier;
        this.masterKey = masterKey;
        this.defaultBaseUrl = defaultBaseUrl;
    }

    /** Test-only compatibility constructor for small isolated service tests. */
    public V014ModelPreferenceService(JdbcTemplate jdbc, PlayerAiService playerAi) {
        this(jdbc, playerAi, null, null, "test-only", "https://api.deepseek.com");
    }

    @PostConstruct
    void ensureCatalog() {
        Instant now = Instant.now();
        if (count("select count(*) from ai_provider_config where provider_code=?", DEFAULT_PROVIDER) == 0) {
            jdbc.update("""
                    insert into ai_provider_config(provider_code, display_name, base_url, adapter_type,
                                                   platform_secret_ciphertext, enabled, user_key_enabled, updated_at)
                    values (?, 'DeepSeek', ?, 'OPENAI_COMPATIBLE', null, true, true, ?)
                    """, DEFAULT_PROVIDER, defaultBaseUrl, Timestamp.from(now));
        }
        seedModel(DEFAULT_PROVIDER, "deepseek-v4-flash", "Flash", now);
        seedModel(DEFAULT_PROVIDER, "deepseek-v4-pro", "Pro", now);
        String legacyPlatformCipher = jdbc.query("""
                select secret_ciphertext from ai_model_config
                where provider_code=? and active=true and secret_ciphertext is not null limit 1
                """, (row, index) -> row.getString(1), DEFAULT_PROVIDER).stream().findFirst().orElse(null);
        if (legacyPlatformCipher != null && !legacyPlatformCipher.isBlank()) {
            jdbc.update("""
                    update ai_provider_config set platform_secret_ciphertext=?, updated_at=?
                    where provider_code=? and platform_secret_ciphertext is null
                    """, legacyPlatformCipher, Timestamp.from(now), DEFAULT_PROVIDER);
        }
        Set<String> migratedUsers = new HashSet<>(jdbc.queryForList("""
                select user_id from user_ai_provider_credential where provider_code=?
                """, String.class, DEFAULT_PROVIDER));
        List<LegacyCredential> legacyCredentials = jdbc.query("""
                select user_id, api_key_ciphertext, masked_suffix, status, last_verified_at,
                       last_error_code, created_at, updated_at
                from user_ai_credential where provider=?
                """, (row, index) -> new LegacyCredential(row.getString(1), row.getString(2), row.getString(3),
                row.getString(4), row.getTimestamp(5), row.getString(6), row.getTimestamp(7), row.getTimestamp(8)),
                DEFAULT_PROVIDER);
        List<Object[]> pendingCredentials = legacyCredentials.stream()
                .filter(credential -> !migratedUsers.contains(credential.userId()))
                .map(credential -> new Object[]{credential.userId(), DEFAULT_PROVIDER, credential.ciphertext(),
                        credential.maskedSuffix(), credential.status(), credential.lastVerifiedAt(),
                        credential.lastErrorCode(), credential.createdAt(), credential.updatedAt()})
                .toList();
        if (!pendingCredentials.isEmpty()) {
            jdbc.batchUpdate("""
                    insert into user_ai_provider_credential(user_id, provider_code, api_key_ciphertext,
                        masked_suffix, status, last_verified_at, last_error_code, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, pendingCredentials);
        }
        RuntimeAiConfigService.RuntimeConfig current = runtimeConfig == null ? null : runtimeConfig.current();
        ensureConfig(PLATFORM_PROVIDER_KEY, current == null ? DEFAULT_PROVIDER : current.provider());
        ensureConfig(PLATFORM_MODEL_KEY, current == null ? DEFAULT_MODEL : current.model());
    }

    public GenerationSettings settings(String userId) {
        ModelPreference stored = stored(userId);
        PlayerAiService.Settings quota = playerAi.settings(userId);
        List<PersonalProvider> providers = jdbc.query("""
                select provider_code, display_name from ai_provider_config
                where enabled=true and user_key_enabled=true order by display_name, provider_code
                """, (row, index) -> personalProvider(userId, row.getString(1), row.getString(2)));
        String mode = stored != null && "USER".equals(stored.credentialSource()) ? "PERSONAL" : "PLATFORM";
        String selectedProvider = "PERSONAL".equals(mode) ? stored.provider() : null;
        String selectedModel = "PERSONAL".equals(mode) ? stored.model() : null;
        boolean available;
        try {
            available = !platformRoute().apiKey().isBlank();
        } catch (RuntimeException unavailable) {
            available = false;
        }
        return new GenerationSettings(mode,
                new PlatformAccess(quota.turnLimit(), quota.usedTurns(), quota.remainingTurns(), available),
                providers, selectedProvider, selectedModel);
    }

    @Transactional
    public GenerationSettings saveSettings(String userId, String mode, String provider, String model) {
        if ("PLATFORM".equals(mode)) {
            ModelPreference previous = stored(userId);
            upsert(userId, previous == null ? DEFAULT_PROVIDER : previous.provider(),
                    previous == null ? DEFAULT_MODEL : previous.model(), "PUBLIC");
            return settings(userId);
        }
        ProviderModel selected = requirePersonalModel(provider, model);
        Credential credential = credential(userId, selected.provider());
        if (credential == null || !"ACTIVE".equals(credential.status())) {
            throw GameApiException.of(HttpStatus.CONFLICT, "PERSONAL_KEY_REQUIRED", "CREDENTIAL",
                    "请先验证自己的推演密钥", "所选推演服务还没有可用密钥，不能切换为自备模式。",
                    List.of("验证并保存密钥后再启用"));
        }
        upsert(userId, selected.provider(), selected.model(), "USER");
        return settings(userId);
    }

    @Transactional
    public GenerationSettings saveCredential(String userId, String provider, String model, String rawApiKey) {
        String apiKey = rawApiKey == null ? "" : rawApiKey.strip();
        if (apiKey.length() < 20 || apiKey.length() > 512 || apiKey.chars().anyMatch(Character::isWhitespace)) {
            throw GameApiException.of(HttpStatus.BAD_REQUEST, "PERSONAL_KEY_FORMAT_INVALID", "CREDENTIAL",
                    "推演密钥格式不正确", "请粘贴完整密钥，内容中不能包含空格。",
                    List.of("重新复制完整密钥后验证"));
        }
        ProviderModel selected = model == null || model.isBlank()
                ? firstPersonalModel(provider) : requirePersonalModel(provider, model);
        verifier.verify(selected.baseUrl(), selected.adapterType(), apiKey, selected.apiModelName());
        Instant now = Instant.now();
        String suffix = apiKey.substring(Math.max(0, apiKey.length() - 4));
        String ciphertext = SecretCipher.encrypt(apiKey, masterKey);
        int updated = jdbc.update("""
                update user_ai_provider_credential
                set api_key_ciphertext=?, masked_suffix=?, status='ACTIVE', last_verified_at=?,
                    last_error_code=null, updated_at=? where user_id=? and provider_code=?
                """, ciphertext, suffix, Timestamp.from(now), Timestamp.from(now), userId, selected.provider());
        if (updated == 0) {
            jdbc.update("""
                    insert into user_ai_provider_credential(user_id, provider_code, api_key_ciphertext,
                        masked_suffix, status, last_verified_at, last_error_code, created_at, updated_at)
                    values (?, ?, ?, ?, 'ACTIVE', ?, null, ?, ?)
                    """, userId, selected.provider(), ciphertext, suffix, Timestamp.from(now),
                    Timestamp.from(now), Timestamp.from(now));
        }
        upsert(userId, selected.provider(), selected.model(), "USER");
        return settings(userId);
    }

    @Transactional
    public GenerationSettings deleteCredential(String userId, String provider) {
        String normalized = normalize(provider);
        jdbc.update("delete from user_ai_provider_credential where user_id=? and provider_code=?", userId, normalized);
        ModelPreference preference = stored(userId);
        if (preference != null && normalized.equals(preference.provider()) && "USER".equals(preference.credentialSource())) {
            upsert(userId, preference.provider(), preference.model(), "PUBLIC");
        }
        return settings(userId);
    }

    /** 将被供应商拒绝的玩家密钥标为失效，后续请求不得继续使用该密钥。 */
    @Transactional
    public void markCredentialInvalid(String userId, String provider, String errorCode) {
        jdbc.update("""
                update user_ai_provider_credential
                set status='INVALID', last_error_code=?, updated_at=?
                where user_id=? and provider_code=?
                """, errorCode, Timestamp.from(Instant.now()), userId, normalize(provider));
    }

    /** Internal compatibility view. Never expose this object on a player-facing platform-mode endpoint. */
    public ModelPreference get(String userId) {
        ModelPreference preference = stored(userId);
        return preference == null ? new ModelPreference(DEFAULT_PROVIDER, DEFAULT_MODEL, "PUBLIC") : preference;
    }

    /** Retained for isolated tests and internal migration callers. */
    public ModelPreference save(String userId, String provider, String model, String credentialSource) {
        saveSettings(userId, "USER".equals(credentialSource) ? "PERSONAL" : "PLATFORM", provider, model);
        return get(userId);
    }

    public ResolvedRoute resolve(String userId, String ignoredLegacyBaseUrl) {
        ModelPreference preference = stored(userId);
        if (preference == null || !"USER".equals(preference.credentialSource())) return platformRoute();
        ProviderModel selected = requirePersonalModel(preference.provider(), preference.model());
        Credential credential = credential(userId, selected.provider());
        if (credential == null || !"ACTIVE".equals(credential.status())) {
            throw GameApiException.of(HttpStatus.CONFLICT, "PERSONAL_KEY_UNAVAILABLE", "CREDENTIAL",
                    "自己的推演密钥不可用", "当前命卷选择了自备模式，但对应密钥不存在或尚未通过验证。",
                    List.of("前往设置重新验证密钥", "切换回平台推演"));
        }
        try {
            String apiKey = SecretCipher.decrypt(credential.ciphertext(), masterKey);
            return new ResolvedRoute(selected.baseUrl(), apiKey, selected.provider(), selected.adapterType(), selected.apiModelName(),
                    "USER", true);
        } catch (RuntimeException error) {
            jdbc.update("""
                    update user_ai_provider_credential set status='INVALID', last_error_code='DECRYPT_FAILED', updated_at=?
                    where user_id=? and provider_code=?
                    """, Timestamp.from(Instant.now()), userId, selected.provider());
            throw GameApiException.of(HttpStatus.CONFLICT, "PERSONAL_KEY_DECRYPT_FAILED", "CREDENTIAL",
                    "自己的推演密钥需要重新保存", "服务器无法读取此前保存的密钥，已停止本次行动且不会消耗平台回合。",
                    List.of("前往设置重新验证密钥"));
        }
    }

    private ResolvedRoute platformRoute() {
        RuntimeAiConfigService.RuntimeConfig legacy = runtimeConfig.current();
        String provider = normalize(configValue(PLATFORM_PROVIDER_KEY, legacy.provider()));
        String model = configValue(PLATFORM_MODEL_KEY, legacy.model());
        ProviderModel selected;
        try {
            selected = requirePlatformModel(provider, model);
        } catch (RuntimeException invalidConfig) {
            selected = requirePlatformModel(legacy.provider(), legacy.model());
        }
        ProviderConfig config = provider(selected.provider());
        String apiKey = "";
        if (config.platformCiphertext() != null && !config.platformCiphertext().isBlank()) {
            apiKey = SecretCipher.decrypt(config.platformCiphertext(), masterKey);
        } else if (selected.provider().equalsIgnoreCase(legacy.provider())) {
            apiKey = legacy.apiKey();
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw GameApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "PLATFORM_GENERATION_UNAVAILABLE", "MODEL",
                    "平台推演暂不可用", "平台推演服务尚未完成配置，请稍后再试。", List.of("稍后再试"));
        }
        return new ResolvedRoute(selected.baseUrl(), apiKey, selected.provider(), selected.adapterType(), selected.apiModelName(),
                "PUBLIC", false);
    }

    private PersonalProvider personalProvider(String userId, String providerCode, String displayName) {
        Credential credential = credential(userId, providerCode);
        List<PersonalModel> models = jdbc.query("""
                select model_code, display_name from ai_provider_model
                where provider_code=? and user_selectable=true order by display_name, model_code
                """, (row, index) -> new PersonalModel(row.getString(1), row.getString(2)), providerCode);
        return new PersonalProvider(providerCode, displayName, credential != null,
                credential == null ? null : credential.status(),
                credential == null ? null : credential.maskedSuffix(),
                credential == null ? null : credential.lastVerifiedAt(), models);
    }

    private ProviderModel firstPersonalModel(String provider) {
        String normalized = normalize(provider);
        return jdbc.query("""
                select p.provider_code, m.model_code, m.api_model_name, p.base_url, p.adapter_type
                from ai_provider_config p join ai_provider_model m on m.provider_code=p.provider_code
                where p.provider_code=? and p.enabled=true and p.user_key_enabled=true and m.user_selectable=true
                order by m.display_name, m.model_code limit 1
                """, (row, index) -> providerModel(row), normalized).stream().findFirst()
                .orElseThrow(() -> unsupportedProvider(normalized));
    }

    private ProviderModel requirePersonalModel(String provider, String model) {
        String normalized = normalize(provider);
        return jdbc.query("""
                select p.provider_code, m.model_code, m.api_model_name, p.base_url, p.adapter_type
                from ai_provider_config p join ai_provider_model m on m.provider_code=p.provider_code
                where p.provider_code=? and m.model_code=? and p.enabled=true and p.user_key_enabled=true
                  and m.user_selectable=true
                """, (row, index) -> providerModel(row), normalized, model).stream().findFirst()
                .orElseThrow(() -> unsupportedProvider(normalized));
    }

    private ProviderModel requirePlatformModel(String provider, String model) {
        String normalized = normalize(provider);
        return jdbc.query("""
                select p.provider_code, m.model_code, m.api_model_name, p.base_url, p.adapter_type
                from ai_provider_config p join ai_provider_model m on m.provider_code=p.provider_code
                where p.provider_code=? and m.model_code=? and p.enabled=true and m.platform_enabled=true
                """, (row, index) -> providerModel(row), normalized, model).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("invalid platform generation route"));
    }

    private ProviderModel providerModel(java.sql.ResultSet row) throws java.sql.SQLException {
        return new ProviderModel(row.getString(1), row.getString(2), row.getString(3),
                row.getString(4), row.getString(5));
    }

    private ProviderConfig provider(String provider) {
        return jdbc.query("""
                select provider_code, platform_secret_ciphertext from ai_provider_config
                where provider_code=? and enabled=true
                """, (row, index) -> new ProviderConfig(row.getString(1), row.getString(2)), provider)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("provider unavailable"));
    }

    private Credential credential(String userId, String provider) {
        return jdbc.query("""
                select api_key_ciphertext, masked_suffix, status, last_verified_at
                from user_ai_provider_credential where user_id=? and provider_code=?
                """, (row, index) -> new Credential(row.getString(1), row.getString(2), row.getString(3),
                row.getTimestamp(4) == null ? null : row.getTimestamp(4).toInstant()), userId, normalize(provider))
                .stream().findFirst().orElse(null);
    }

    private ModelPreference stored(String userId) {
        return jdbc.query("""
                select provider_code, model_code, credential_source from v014_user_model_preference where user_id=?
                """, (row, index) -> new ModelPreference(row.getString(1), row.getString(2), row.getString(3)), userId)
                .stream().findFirst().orElse(null);
    }

    private void upsert(String userId, String provider, String model, String source) {
        Instant now = Instant.now();
        int changed = jdbc.update("""
                update v014_user_model_preference set provider_code=?, model_code=?, credential_source=?, updated_at=?
                where user_id=?
                """, normalize(provider), model, source, Timestamp.from(now), userId);
        if (changed == 0) {
            jdbc.update("""
                    insert into v014_user_model_preference(user_id, provider_code, model_code, credential_source, updated_at)
                    values (?, ?, ?, ?, ?)
                    """, userId, normalize(provider), model, source, Timestamp.from(now));
        }
    }

    private void seedModel(String provider, String model, String displayName, Instant now) {
        if (count("select count(*) from ai_provider_model where provider_code=? and model_code=?", provider, model) == 0) {
            jdbc.update("""
                    insert into ai_provider_model(provider_code, model_code, display_name, api_model_name,
                                                  platform_enabled, user_selectable, updated_at)
                    values (?, ?, ?, ?, true, true, ?)
                    """, provider, model, displayName, model, Timestamp.from(now));
        }
    }

    private void ensureConfig(String key, String value) {
        if (count("select count(*) from gameplay_config where config_key=?", key) == 0) {
            jdbc.update("insert into gameplay_config(config_key, config_value, updated_at) values (?, ?, ?)",
                    key, value, Timestamp.from(Instant.now()));
        }
    }

    private String configValue(String key, String fallback) {
        return jdbc.query("select config_value from gameplay_config where config_key=?",
                (row, index) -> row.getString(1), key).stream().findFirst().orElse(fallback);
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private GameApiException unsupportedProvider(String provider) {
        return GameApiException.of(HttpStatus.BAD_REQUEST, "PERSONAL_MODEL_UNAVAILABLE", "CREDENTIAL",
                "所选推演方式暂不可用", "当前没有开放这个供应商或模型。", List.of("重新选择可用模型"));
    }

    private String normalize(String provider) {
        return provider == null ? "" : provider.strip().toUpperCase(Locale.ROOT);
    }

    public record ResolvedRoute(String baseUrl, String apiKey, String provider, String adapterType, String model,
                                String credentialSource, boolean hasCustomCredential) {}

    private record ProviderModel(String provider, String model, String apiModelName,
                                 String baseUrl, String adapterType) {}
    private record ProviderConfig(String provider, String platformCiphertext) {}
    private record Credential(String ciphertext, String maskedSuffix, String status, Instant lastVerifiedAt) {}
    private record LegacyCredential(String userId, String ciphertext, String maskedSuffix, String status,
                                    Timestamp lastVerifiedAt, String lastErrorCode,
                                    Timestamp createdAt, Timestamp updatedAt) {}
}

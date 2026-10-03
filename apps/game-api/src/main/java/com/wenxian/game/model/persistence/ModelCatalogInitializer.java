package com.wenxian.game.model.persistence;

import com.wenxian.game.config.SecretCipher;
import com.wenxian.game.model.domain.ModelTask;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 初始化中性模型目录，并在空环境中安全接续服务器提供的初始平台密钥。
 *
 * <p>该初始化器只补齐缺失配置，不覆盖管理台已经调整的模型或任务路由。它是稳定模型平台的启动边界，
 * 新故事链不再读取历史模型表。已有历史密钥与偏好必须由发布前的一次性迁移脚本接续。</p>
 */
@Component
public class ModelCatalogInitializer {
    private static final String DEFAULT_PROVIDER = "DEEPSEEK";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    private final JdbcTemplate jdbc;
    private final String defaultBaseUrl;
    private final String defaultApiKey;
    private final String defaultModel;
    private final String masterKey;
    private final boolean forceEnvironmentKey;

    public ModelCatalogInitializer(JdbcTemplate jdbc,
                                   @Value("${deepseek.base-url:https://api.deepseek.com}") String defaultBaseUrl,
                                   @Value("${deepseek.api-key:}") String defaultApiKey,
                                   @Value("${deepseek.model:deepseek-v4-flash}") String defaultModel,
                                   @Value("${game.ai-config-master-key:wenxian-local-config-key-change-me}") String masterKey,
                                   @Value("${game.model-catalog-force-environment-key:false}") boolean forceEnvironmentKey) {
        this.jdbc = jdbc;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultApiKey = defaultApiKey == null ? "" : defaultApiKey.strip();
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? DEFAULT_MODEL : defaultModel.strip();
        this.masterKey = masterKey;
        this.forceEnvironmentKey = forceEnvironmentKey;
    }

    @PostConstruct
    public void initialize() {
        Instant now = Instant.now();
        ensureProvider(now);
        ensureModel(DEFAULT_PROVIDER, "deepseek-v4-flash", "Flash", now);
        ensureModel(DEFAULT_PROVIDER, "deepseek-v4-pro", "Pro", now);
        seedPlatformCredential(now);
        ensureTaskRoutes(now);
    }

    private void ensureProvider(Instant now) {
        if (count("select count(*) from ai_provider_config where provider_code=?", DEFAULT_PROVIDER) > 0) return;
        jdbc.update("""
                insert into ai_provider_config(provider_code, display_name, base_url, adapter_type,
                                               platform_secret_ciphertext, enabled, user_key_enabled, updated_at)
                values (?, 'DeepSeek', ?, 'OPENAI_COMPATIBLE', null, true, true, ?)
                """, DEFAULT_PROVIDER, defaultBaseUrl, Timestamp.from(now));
    }

    private void ensureModel(String provider, String model, String displayName, Instant now) {
        if (count("select count(*) from ai_provider_model where provider_code=? and model_code=?",
                provider, model) > 0) return;
        jdbc.update("""
                insert into ai_provider_model(provider_code, model_code, display_name, api_model_name,
                    capability_codes, context_window, maximum_output_tokens,
                    platform_enabled, user_selectable, updated_at)
                values (?, ?, ?, ?, 'CHAT,JSON_MODE,THINKING_CONTROL,CACHE_USAGE', 128000, 8000,
                        true, true, ?)
                """, provider, model, displayName, model, Timestamp.from(now));
    }

    private void seedPlatformCredential(Instant now) {
        if (defaultApiKey.isBlank()) return;
        String existing = jdbc.queryForObject("""
                select coalesce(platform_secret_ciphertext, '') from ai_provider_config where provider_code=?
                """, String.class, DEFAULT_PROVIDER);
        if (!forceEnvironmentKey && existing != null && !existing.isBlank()) return;
        jdbc.update("""
                update ai_provider_config
                set platform_secret_ciphertext=?, config_version=config_version + 1, updated_at=?
                where provider_code=?
                """, SecretCipher.encrypt(defaultApiKey, masterKey), Timestamp.from(now), DEFAULT_PROVIDER);
    }

    private void ensureTaskRoutes(Instant now) {
        String initialModel = count("select count(*) from ai_provider_model where provider_code=? and model_code=?",
                DEFAULT_PROVIDER, defaultModel) > 0 ? defaultModel : DEFAULT_MODEL;
        for (ModelTask task : ModelTask.values()) {
            if (count("select count(*) from ai_model_task_route where task_code=?", task.name()) > 0) continue;
            jdbc.update("""
                    insert into ai_model_task_route(task_code, primary_provider_code, primary_model_code,
                        temperature, timeout_seconds, maximum_retries, maximum_output_tokens,
                        disable_thinking, fallback_enabled, updated_at)
                    values (?, ?, ?, ?, 90, 0, ?, true, false, ?)
                    """, task.name(), DEFAULT_PROVIDER, initialModel, temperature(task), outputLimit(task),
                    Timestamp.from(now));
        }
    }

    private double temperature(ModelTask task) {
        return switch (task) {
            case STORY_CORRECTION -> 0.1;
            case TURN_DIRECTOR -> 0.3;
            case NARRATIVE_WRITER -> 0.8;
            case CONTEXT_SUMMARY -> 0.2;
            case FORMAT_REPAIR -> 0;
            default -> 0.7;
        };
    }

    private Integer outputLimit(ModelTask task) {
        return switch (task) {
            case TURN_DIRECTOR, CHARACTER_CREATION -> null;
            case STORY_CORRECTION -> 2200;
            case NARRATIVE_WRITER -> 3200;
            case CONTEXT_SUMMARY, FORMAT_REPAIR -> 1800;
            default -> 2400;
        };
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }
}

package com.wenxian.game.model.persistence;

import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import com.wenxian.game.model.domain.ModelTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 使用中性模型表读取模型能力、任务路由、偏好和加密凭据。
 *
 * <p>本仓储不依赖产品版本包，不开启业务事务，也不返回解密后的密钥。未知能力编码会立即失败，
 * 防止系统把未经验证的模型能力带入生成链路。</p>
 */
@Repository
public class JdbcModelCatalogRepository implements ModelCatalogRepository {
    private final JdbcTemplate jdbc;

    public JdbcModelCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ModelDefinition> findPlatformModel(String providerCode, String modelCode) {
        return findModel(providerCode, modelCode, "m.platform_enabled=true");
    }

    @Override
    public Optional<ModelDefinition> findUserSelectableModel(String providerCode, String modelCode) {
        return findModel(providerCode, modelCode, "p.user_key_enabled=true and m.user_selectable=true");
    }

    @Override
    public Optional<TaskRoute> findTaskRoute(ModelTask task) {
        return jdbc.query("""
                select task_code, primary_provider_code, primary_model_code,
                       fallback_provider_code, fallback_model_code, temperature, timeout_seconds,
                       maximum_retries, maximum_output_tokens, disable_thinking, fallback_enabled
                from ai_model_task_route where task_code=?
                """, (row, index) -> taskRoute(row), task.name()).stream().findFirst();
    }

    @Override
    public Optional<UserPreference> findUserPreference(String userId) {
        return jdbc.query("""
                select provider_code, model_code, credential_source
                from user_model_preference where user_id=?
                """, (row, index) -> new UserPreference(row.getString(1), row.getString(2), row.getString(3)),
                userId).stream().findFirst();
    }

    @Override
    public Optional<EncryptedCredential> findPlatformCredential(String providerCode) {
        return jdbc.query("""
                select platform_secret_ciphertext from ai_provider_config
                where provider_code=? and enabled=true and platform_secret_ciphertext is not null
                """, (row, index) -> new EncryptedCredential(row.getString(1)), normalize(providerCode))
                .stream().filter(value -> value.ciphertext() != null && !value.ciphertext().isBlank()).findFirst();
    }

    @Override
    public Optional<EncryptedCredential> findActiveUserCredential(String userId, String providerCode) {
        return jdbc.query("""
                select api_key_ciphertext from user_ai_provider_credential
                where user_id=? and provider_code=? and status='ACTIVE'
                """, (row, index) -> new EncryptedCredential(row.getString(1)), userId, normalize(providerCode))
                .stream().filter(value -> value.ciphertext() != null && !value.ciphertext().isBlank()).findFirst();
    }

    private Optional<ModelDefinition> findModel(String providerCode, String modelCode, String accessCondition) {
        String sql = """
                select p.provider_code, m.model_code, p.base_url, p.adapter_type, m.api_model_name,
                       m.capability_codes, m.context_window, m.maximum_output_tokens
                from ai_provider_config p
                join ai_provider_model m on m.provider_code=p.provider_code
                where p.provider_code=? and m.model_code=? and p.enabled=true and %s
                """.formatted(accessCondition);
        return jdbc.query(sql, (row, index) -> model(row), normalize(providerCode), modelCode)
                .stream().findFirst();
    }

    private ModelDefinition model(ResultSet row) throws SQLException {
        return new ModelDefinition(row.getString(1), row.getString(2), row.getString(3),
                ModelAdapterType.valueOf(row.getString(4).strip().toUpperCase(Locale.ROOT)),
                row.getString(5), capabilities(row.getString(6)), row.getInt(7), row.getInt(8));
    }

    private TaskRoute taskRoute(ResultSet row) throws SQLException {
        Integer maximumOutput = row.getObject(9, Integer.class);
        ModelInvocationOptions options = new ModelInvocationOptions(row.getDouble(6),
                Duration.ofSeconds(row.getInt(7)), row.getInt(8), maximumOutput, row.getBoolean(10));
        return new TaskRoute(ModelTask.valueOf(row.getString(1)), row.getString(2), row.getString(3),
                row.getString(4), row.getString(5), options, row.getBoolean(11));
    }

    private Set<ModelCapability> capabilities(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        try {
            return Arrays.stream(raw.split(","))
                    .map(String::strip)
                    .filter(value -> !value.isBlank())
                    .map(value -> ModelCapability.valueOf(value.toUpperCase(Locale.ROOT)))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("模型目录包含未知能力编码", invalid);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }
}

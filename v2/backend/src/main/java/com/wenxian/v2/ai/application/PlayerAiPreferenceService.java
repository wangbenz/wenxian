package com.wenxian.v2.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenxian.v2.api.V2ApiException;
import com.wenxian.v2.config.AiProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlayerAiPreferenceService {
    private final JdbcTemplate jdbc;
    private final AiProperties properties;
    private final RestClient.Builder clients;
    private final SponsoredTurnQuotaService quotas;

    public PlayerAiPreferenceService(JdbcTemplate jdbc, AiProperties properties, RestClient.Builder clients,
                                     SponsoredTurnQuotaService quotas) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clients = clients;
        this.quotas = quotas;
    }

    public Settings settings(String userId) {
        List<ModelOption> models = jdbc.query("""
                select m.model_code,m.provider_code,m.display_name
                from ai_model_catalog m join ai_provider_config p on p.provider_code=m.provider_code
                where m.active=true and p.active=true order by m.provider_code,m.display_name
                """, (row, index) -> new ModelOption(row.getString("model_code"),
                row.getString("provider_code"), row.getString("display_name")));
        Preference preference = jdbc.query("""
                        select credential_source,provider_code,model_code from user_ai_preference where user_id=?
                        """, (row, index) -> new Preference(row.getString("credential_source"),
                        row.getString("provider_code"), row.getString("model_code")), userId)
                .stream().findFirst().orElseGet(() -> platformPreference());
        Credential credential = jdbc.query("""
                        select masked_suffix,credential_status,last_verified_at
                        from user_ai_provider_credential where user_id=? and provider_code='DEEPSEEK'
                        """, (row, index) -> new Credential(row.getString("masked_suffix"),
                        row.getString("credential_status"), row.getTimestamp("last_verified_at") == null
                        ? null : row.getTimestamp("last_verified_at").toInstant()), userId)
                .stream().findFirst().orElse(null);
        SponsoredTurnQuotaService.Quota quota = quotas.quota(userId);
        boolean customEnabled = customApiEnabled();
        String effectiveSource = customEnabled ? preference.source() : "PLATFORM";
        return new Settings(effectiveSource, preference.provider(), preference.model(), models,
                credential != null, credential == null ? null : credential.maskedSuffix(),
                credential == null ? null : credential.status(),
                credential == null ? null : credential.verifiedAt(),
                properties.credentialEncryptionAvailable() && customEnabled,
                quota.turnLimit(), quota.usedTurns(), quota.remainingTurns(), quota.exhaustedMessage(),
                quota.contactText());
    }

    @Transactional
    public Settings choose(String userId, String source, String modelCode) {
        String normalized = source == null ? "" : source.strip().toUpperCase();
        if (!List.of("PLATFORM", "USER").contains(normalized)) {
            throw V2ApiException.badRequest("AI_PREFERENCE_SOURCE_INVALID", "推演来源只能选择平台或自己的密钥。");
        }
        ModelOption model = requireModel(modelCode);
        if ("USER".equals(normalized)) {
            if (!customApiEnabled()) {
                throw V2ApiException.conflict("USER_AI_CREDENTIAL_DISABLED",
                        "自己的推演密钥当前没有开放。", "切换回游戏提供");
            }
            Integer active = jdbc.queryForObject("""
                    select count(*) from user_ai_provider_credential
                    where user_id=? and provider_code=? and credential_status='ACTIVE'
                    """, Integer.class, userId, model.providerCode());
            if (active == null || active == 0) {
                throw V2ApiException.conflict("USER_AI_CREDENTIAL_REQUIRED",
                        "请先验证自己的推演密钥。", "粘贴完整密钥并验证");
            }
        }
        upsertPreference(userId, normalized, model.providerCode(), model.modelCode(), Instant.now());
        return settings(userId);
    }

    @Transactional
    public Settings saveCredential(String userId, String rawApiKey, String modelCode) {
        if (!customApiEnabled()) {
            throw V2ApiException.conflict("USER_AI_CREDENTIAL_DISABLED",
                    "自己的推演密钥当前没有开放。", "稍后再试");
        }
        if (!properties.credentialEncryptionAvailable()) {
            throw new V2ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_CREDENTIAL_ENCRYPTION_NOT_CONFIGURED",
                    "SERVICE", "自己的推演密钥暂未开放。", List.of("稍后再试"));
        }
        String apiKey = rawApiKey == null ? "" : rawApiKey.strip();
        if (apiKey.length() < 20 || apiKey.length() > 512 || apiKey.chars().anyMatch(Character::isWhitespace)) {
            throw V2ApiException.badRequest("USER_AI_CREDENTIAL_FORMAT_INVALID",
                    "密钥格式不正确，请粘贴完整内容且不要包含空格。", "重新复制后验证");
        }
        ModelOption model = requireModel(modelCode);
        String baseUrl = jdbc.queryForObject("select official_base_url from ai_provider_config where provider_code=?",
                String.class, model.providerCode());
        verify(baseUrl, apiKey, model.modelCode());
        Instant now = Instant.now();
        String ciphertext = V2SecretCipher.encrypt(apiKey, properties.credentialMasterKey());
        String suffix = apiKey.substring(apiKey.length() - 4);
        int updated = jdbc.update("""
                update user_ai_provider_credential set api_key_ciphertext=?,masked_suffix=?,
                    credential_status='ACTIVE',last_verified_at=?,last_error_code=null,updated_at=?
                where user_id=? and provider_code=?
                """, ciphertext, suffix, Timestamp.from(now), Timestamp.from(now), userId, model.providerCode());
        if (updated == 0) {
            jdbc.update("""
                    insert into user_ai_provider_credential(user_id,provider_code,api_key_ciphertext,
                        masked_suffix,credential_status,last_verified_at,last_error_code,created_at,updated_at)
                    values (?,?,?,?,'ACTIVE',?,null,?,?)
                    """, userId, model.providerCode(), ciphertext, suffix, Timestamp.from(now),
                    Timestamp.from(now), Timestamp.from(now));
        }
        upsertPreference(userId, "USER", model.providerCode(), model.modelCode(), now);
        return settings(userId);
    }

    @Transactional
    public Settings deleteCredential(String userId) {
        jdbc.update("delete from user_ai_provider_credential where user_id=? and provider_code='DEEPSEEK'", userId);
        Preference platform = platformPreference();
        upsertPreference(userId, "PLATFORM", platform.provider(), platform.model(), Instant.now());
        return settings(userId);
    }

    private void verify(String baseUrl, String apiKey, String modelCode) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", modelCode);
            payload.put("messages", List.of(Map.of("role", "user", "content", "只回复：可用")));
            payload.put("temperature", 0);
            payload.put("max_tokens", 4);
            payload.put("stream", false);
            payload.put("thinking", Map.of("type", "disabled"));
            JsonNode response = clients.baseUrl(baseUrl).build().post().uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey).body(payload)
                    .retrieve().body(JsonNode.class);
            if (response == null || !response.path("choices").isArray()) {
                throw new RestClientException("missing choices");
            }
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 401) {
                throw V2ApiException.badRequest("USER_AI_CREDENTIAL_INVALID", "密钥验证未通过，请检查是否复制完整。",
                        "重新复制完整密钥后验证");
            }
            if (error.getStatusCode().value() == 402) {
                throw V2ApiException.badRequest("USER_AI_CREDENTIAL_BALANCE_EMPTY", "该密钥所属账户余额不足。",
                        "补充余额后重试", "更换其他密钥");
            }
            throw temporaryCredentialError();
        } catch (RestClientException error) {
            throw temporaryCredentialError();
        }
    }

    private V2ApiException temporaryCredentialError() {
        return new V2ApiException(HttpStatus.BAD_GATEWAY, "USER_AI_CREDENTIAL_VERIFY_UNAVAILABLE", "SERVICE",
                "当前没有完成密钥验证，原有设置未改变。", List.of("稍后重新验证"));
    }

    private ModelOption requireModel(String modelCode) {
        return jdbc.query("""
                        select m.model_code,m.provider_code,m.display_name from ai_model_catalog m
                        join ai_provider_config p on p.provider_code=m.provider_code
                        where m.model_code=? and m.active=true and p.active=true
                        """, (row, index) -> new ModelOption(row.getString("model_code"),
                        row.getString("provider_code"), row.getString("display_name")), modelCode)
                .stream().findFirst().orElseThrow(() -> V2ApiException.badRequest("AI_MODEL_INVALID",
                        "所选推演模型当前不可用。"));
    }

    private Preference platformPreference() {
        return jdbc.query("""
                        select r.provider_code,r.primary_model_code from ai_task_route r
                        where r.task_type='NARRATIVE_WRITING'
                        """, (row, index) -> new Preference("PLATFORM", row.getString("provider_code"),
                        row.getString("primary_model_code"))).stream().findFirst()
                .orElseThrow(() -> V2ApiException.conflict("AI_ROUTE_UNAVAILABLE", "当前没有可用的推演模型。"));
    }

    private void upsertPreference(String userId, String source, String provider, String model, Instant now) {
        int updated = jdbc.update("""
                update user_ai_preference set credential_source=?,provider_code=?,model_code=?,updated_at=?
                where user_id=?
                """, source, provider, model, Timestamp.from(now), userId);
        if (updated == 0) {
            jdbc.update("""
                    insert into user_ai_preference(user_id,credential_source,provider_code,model_code,updated_at)
                    values (?,?,?,?,?)
                    """, userId, source, provider, model, Timestamp.from(now));
        }
    }

    private boolean customApiEnabled() {
        return jdbc.query("select config_value from gameplay_config where config_key='custom_api_enabled'",
                (row, index) -> Boolean.parseBoolean(row.getString(1))).stream().findFirst().orElse(true);
    }

    public record ModelOption(String modelCode, String providerCode, String displayName) {
    }

    public record Settings(String credentialSource, String providerCode, String modelCode,
                           List<ModelOption> models, boolean credentialConfigured, String maskedSuffix,
                           String credentialStatus, Instant lastVerifiedAt, boolean userCredentialEnabled,
                           int turnLimit, int usedTurns, int remainingTurns, String exhaustedMessage,
                           String contactText) {
    }

    private record Preference(String source, String provider, String model) {
    }

    private record Credential(String maskedSuffix, String status, Instant verifiedAt) {
    }
}

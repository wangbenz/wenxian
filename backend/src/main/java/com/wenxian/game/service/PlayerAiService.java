package com.wenxian.game.service;

import com.wenxian.game.api.GameApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.wenxian.game.config.RuntimeAiConfigService;
import com.wenxian.game.config.SecretCipher;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PlayerAiService {
    public static final String PUBLIC_SOURCE = "PUBLIC";
    public static final String USER_SOURCE = "USER";
    private static final String ACCOUNT_LIMIT_KEY = "account_public_turn_limit";
    private static final String ACCOUNT_LIMIT_MESSAGE_KEY = "account_public_turn_limit_message";
    private static final String CUSTOM_API_KEY = "custom_api_enabled";
    private static final String CONTACT_TEXT_KEY = "registration_contact_text";
    private static final String DEFAULT_LIMIT_MESSAGE = "本账号获赠的推演回合已经用完。你可以前往「世界」接入自己的推演密钥继续游玩。";
    private static final String DEFAULT_CONTACT_TEXT = "如需参与测试或反馈问题，请加入QQ群：1092478336。";
    private static final Pattern SUPPORT_GROUP_PATTERN = Pattern.compile("(?<!\\d)(\\d{5,12})(?!\\d)");
    private static final ZoneId QUOTA_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final RuntimeAiConfigService runtimeConfig;
    private final RestClient restClient;
    private final String masterKey;

    public PlayerAiService(JdbcTemplate jdbc,
                           RuntimeAiConfigService runtimeConfig,
                           RestClient.Builder restClientBuilder,
                           @Value("${deepseek.base-url:https://api.deepseek.com}") String baseUrl,
                           @Value("${game.ai-config-master-key:wenxian-local-config-key-change-me}") String masterKey) {
        this.jdbc = jdbc;
        this.runtimeConfig = runtimeConfig;
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.masterKey = masterKey;
    }

    @PostConstruct
    void ensureDefaults() {
        ensureConfig(ACCOUNT_LIMIT_KEY, "20");
        ensureConfig(ACCOUNT_LIMIT_MESSAGE_KEY, DEFAULT_LIMIT_MESSAGE);
        ensureConfig(CUSTOM_API_KEY, "true");
    }

    public Settings settings(String userId) {
        Credential credential = credential(userId);
        int used = usedTurns(userId);
        int limit = accountLimit();
        boolean customEnabled = customApiEnabled();
        boolean usingCustom = customEnabled && credential != null && "ACTIVE".equals(credential.status());
        return new Settings(limit, used, Math.max(0, limit - used), customEnabled,
                usingCustom, credential != null, credential == null ? null : credential.maskedSuffix(),
                credential == null ? null : credential.status(),
                credential == null ? null : credential.lastVerifiedAt());
    }

    public Route route(String userId) {
        RuntimeAiConfigService.RuntimeConfig shared = runtimeConfig.current();
        if (!customApiEnabled()) return new Route(shared, PUBLIC_SOURCE);
        Credential credential = credential(userId);
        if (credential == null || !"ACTIVE".equals(credential.status())) {
            return new Route(shared, PUBLIC_SOURCE);
        }
        try {
            String apiKey = SecretCipher.decrypt(credential.ciphertext(), masterKey);
            return new Route(new RuntimeAiConfigService.RuntimeConfig(
                    shared.provider(), shared.model(), shared.displayName(), apiKey), USER_SOURCE);
        } catch (RuntimeException error) {
            jdbc.update("update user_ai_credential set status='INVALID', last_error_code='DECRYPT_FAILED', updated_at=? where user_id=?",
                    Timestamp.from(Instant.now()), userId);
            return new Route(shared, PUBLIC_SOURCE);
        }
    }

    public void assertPublicQuota(String userId, Route route) {
        if (route.custom()) return;
        int limit = accountLimit();
        int used = usedTurns(userId);
        if (used >= limit) {
            String contactText = configValue(CONTACT_TEXT_KEY, DEFAULT_CONTACT_TEXT);
            throw new PublicTurnLimitException(
                    configValue(ACCOUNT_LIMIT_MESSAGE_KEY, DEFAULT_LIMIT_MESSAGE),
                    limit,
                    contactText,
                    supportGroup(contactText));
        }
    }

    private String supportGroup(String contactText) {
        Matcher matcher = SUPPORT_GROUP_PATTERN.matcher(contactText == null ? "" : contactText);
        return matcher.find() ? matcher.group(1) : "";
    }

    @Transactional
    public void recordSuccessfulInvocation(String userId, Route route) {
        if (route.custom()) return;
        LocalDate today = LocalDate.now(QUOTA_ZONE);
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbc.update("update user_daily_quota set used_count=used_count+1, updated_at=? where user_id=? and usage_date=?",
                now, userId, Date.valueOf(today));
        if (updated == 0) {
            try {
                jdbc.update("insert into user_daily_quota(user_id, usage_date, used_count, updated_at) values (?, ?, 1, ?)",
                        userId, Date.valueOf(today), now);
            } catch (DuplicateKeyException race) {
                jdbc.update("update user_daily_quota set used_count=used_count+1, updated_at=? where user_id=? and usage_date=?",
                        now, userId, Date.valueOf(today));
            }
        }
    }

    @Transactional
    public Settings saveCredential(String userId, String rawApiKey) {
        if (!customApiEnabled()) {
            throw GameApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "CUSTOM_KEY_DISABLED", "CREDENTIAL",
                    "自己的推演密钥暂未开放", "当前暂时不能保存或更换推演密钥。",
                    List.of("稍后再试"));
        }
        String apiKey = rawApiKey == null ? "" : rawApiKey.strip();
        if (apiKey.length() < 20 || apiKey.length() > 512 || apiKey.chars().anyMatch(Character::isWhitespace)) {
            throw GameApiException.of(HttpStatus.BAD_REQUEST, "CUSTOM_KEY_FORMAT_INVALID", "CREDENTIAL",
                    "推演密钥格式不正确", "请粘贴完整密钥，内容中不能包含空格。",
                    List.of("重新复制完整密钥后验证"));
        }
        RuntimeAiConfigService.RuntimeConfig shared = runtimeConfig.current();
        verify(apiKey, shared.model());
        Instant now = Instant.now();
        String suffix = apiKey.substring(Math.max(0, apiKey.length() - 4));
        String ciphertext = SecretCipher.encrypt(apiKey, masterKey);
        int updated = jdbc.update("""
                update user_ai_credential
                set api_key_ciphertext=?, masked_suffix=?, status='ACTIVE', last_verified_at=?,
                    last_error_code=null, updated_at=? where user_id=?
                """, ciphertext, suffix, Timestamp.from(now), Timestamp.from(now), userId);
        if (updated == 0) {
            jdbc.update("""
                    insert into user_ai_credential(user_id, provider, api_key_ciphertext, masked_suffix, status,
                                                   last_verified_at, last_error_code, created_at, updated_at)
                    values (?, 'DEEPSEEK', ?, ?, 'ACTIVE', ?, null, ?, ?)
                    """, userId, ciphertext, suffix, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        }
        return settings(userId);
    }

    @Transactional
    public void deleteCredential(String userId) {
        jdbc.update("delete from user_ai_credential where user_id=?", userId);
    }

    public int accountLimit() {
        String value = configValue(ACCOUNT_LIMIT_KEY, "20");
        try {
            return Math.max(1, Math.min(1000, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return 20;
        }
    }

    public boolean customApiEnabled() {
        return Boolean.parseBoolean(configValue(CUSTOM_API_KEY, "true"));
    }

    private int usedTurns(String userId) {
        Integer used = jdbc.queryForObject(
                "select coalesce(sum(used_count), 0) from user_daily_quota where user_id=?",
                Integer.class, userId);
        return used == null ? 0 : used;
    }

    private Credential credential(String userId) {
        return jdbc.query("""
                        select api_key_ciphertext, masked_suffix, status, last_verified_at
                        from user_ai_credential where user_id=?
                        """, (row, index) -> new Credential(row.getString("api_key_ciphertext"),
                        row.getString("masked_suffix"), row.getString("status"),
                        row.getTimestamp("last_verified_at") == null ? null : row.getTimestamp("last_verified_at").toInstant()),
                userId).stream().findFirst().orElse(null);
    }

    private void verify(String apiKey, String model) {
        try {
            Map<String, Object> request = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", "只回复：可用")),
                    "thinking", Map.of("type", "disabled"),
                    "temperature", 0,
                    "max_tokens", 4,
                    "stream", false
            );
            JsonNode response = restClient.post().uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(request).retrieve().body(JsonNode.class);
            if (response == null || response.path("choices").isMissingNode()) {
                throw GameApiException.temporary("CUSTOM_KEY_VERIFY_UNAVAILABLE", "密钥暂时无法验证",
                        "推演服务没有返回验证结果，密钥尚未保存。", List.of("稍后重新验证"),
                        "credential verification returned no choices", null);
            }
        } catch (RestClientResponseException error) {
            throw switch (error.getStatusCode().value()) {
                case 401 -> GameApiException.of(HttpStatus.BAD_REQUEST, "CUSTOM_KEY_INVALID", "CREDENTIAL",
                        "推演密钥无效", "密钥验证未通过，请检查是否复制完整或已经失效。",
                        List.of("重新复制完整密钥后验证"));
                case 402 -> GameApiException.of(HttpStatus.BAD_REQUEST, "CUSTOM_KEY_BALANCE_EMPTY", "CREDENTIAL",
                        "密钥账户余额不足", "该密钥所属账户当前无法继续生成内容。",
                        List.of("为对应账户补充余额", "更换其他可用密钥"));
                case 429 -> GameApiException.of(HttpStatus.TOO_MANY_REQUESTS, "CUSTOM_KEY_RATE_LIMITED", "CREDENTIAL",
                        "密钥请求过于频繁", "推演服务暂时限制了该密钥的请求频率。",
                        List.of("稍后重新验证"));
                default -> GameApiException.temporary("CUSTOM_KEY_VERIFY_UNAVAILABLE", "密钥暂时无法验证",
                        "推演服务暂时没有完成验证，密钥尚未保存。", List.of("稍后重新验证"),
                        "credential verify status=" + error.getStatusCode().value(), error);
            };
        } catch (RestClientException error) {
            throw GameApiException.temporary("CUSTOM_KEY_VERIFY_UNAVAILABLE", "密钥暂时无法验证",
                    "当前无法连接推演服务，密钥尚未保存。", List.of("检查网络后重新验证", "稍后再试"),
                    error.getMessage(), error);
        }
    }

    private void ensureConfig(String key, String value) {
        Integer count = jdbc.queryForObject("select count(*) from gameplay_config where config_key=?", Integer.class, key);
        if (count != null && count == 0) {
            jdbc.update("insert into gameplay_config(config_key, config_value, updated_at) values (?, ?, ?)",
                    key, value, Timestamp.from(Instant.now()));
        }
    }

    private String configValue(String key, String fallback) {
        return jdbc.query("select config_value from gameplay_config where config_key=?",
                (row, index) -> row.getString("config_value"), key).stream().findFirst().orElse(fallback);
    }

    public record Settings(int turnLimit, int usedTurns, int remainingTurns, boolean customApiEnabled,
                           boolean usingCustomApi, boolean credentialConfigured, String maskedSuffix,
                           String credentialStatus, Instant lastVerifiedAt) {}

    public record Route(RuntimeAiConfigService.RuntimeConfig config, String source) {
        public boolean custom() { return USER_SOURCE.equals(source); }
        public String recordedModel() { return config.model() + (custom() ? "@USER" : "@PUBLIC"); }
    }

    private record Credential(String ciphertext, String maskedSuffix, String status, Instant lastVerifiedAt) {}
}

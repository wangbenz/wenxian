package com.wenxian.v2.ai.persistence;

import com.wenxian.v2.api.V2ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiModelRouteRepository {
    private final JdbcTemplate jdbc;

    public AiModelRouteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ModelRoute require(String taskType) {
        return require(taskType, null);
    }

    public ModelRoute require(String taskType, String userId) {
        if (userId != null && !userId.isBlank()) {
            ModelRoute preferred = preferred(userId);
            if (preferred != null) return preferred;
        }
        return jdbc.query("""
                        select r.task_type,r.provider_code,r.primary_model_code,p.official_base_url
                        from ai_task_route r
                        join ai_provider_config p on p.provider_code=r.provider_code and p.active=true
                        join ai_model_catalog m on m.model_code=r.primary_model_code and m.active=true
                        where r.task_type=?
                        """, (row, index) -> new ModelRoute(row.getString("task_type"),
                        row.getString("provider_code"), row.getString("primary_model_code"),
                        row.getString("official_base_url"), "PLATFORM", null), taskType)
                .stream().findFirst()
                .orElseThrow(() -> V2ApiException.conflict("AI_ROUTE_UNAVAILABLE",
                        "当前推演任务没有可用模型。", "稍后重试或联系管理员"));
    }

    private ModelRoute preferred(String userId) {
        boolean customEnabled = jdbc.query(
                "select config_value from gameplay_config where config_key='custom_api_enabled'",
                (row, index) -> Boolean.parseBoolean(row.getString(1))).stream().findFirst().orElse(true);
        if (!customEnabled) return null;
        var preferences = jdbc.query("""
                        select pref.credential_source,pref.provider_code,pref.model_code,p.official_base_url,
                               m.active as model_active,p.active as provider_active,c.api_key_ciphertext,
                               c.credential_status
                        from user_ai_preference pref
                        join ai_provider_config p on p.provider_code=pref.provider_code
                        join ai_model_catalog m on m.model_code=pref.model_code and m.provider_code=pref.provider_code
                        left join user_ai_provider_credential c on c.user_id=pref.user_id
                            and c.provider_code=pref.provider_code
                        where pref.user_id=?
                        """, (row, index) -> new Preference(row.getString("credential_source"),
                        row.getString("provider_code"), row.getString("model_code"),
                        row.getString("official_base_url"), row.getBoolean("model_active"),
                        row.getBoolean("provider_active"), row.getString("api_key_ciphertext"),
                        row.getString("credential_status")), userId);
        if (preferences.isEmpty() || "PLATFORM".equals(preferences.getFirst().source())) return null;
        Preference preference = preferences.getFirst();
        if (!"USER".equals(preference.source()) || !preference.modelActive() || !preference.providerActive()
                || !"ACTIVE".equals(preference.credentialStatus()) || preference.ciphertext() == null) {
            throw V2ApiException.conflict("USER_AI_CREDENTIAL_UNAVAILABLE",
                    "自己的推演密钥当前不可用，系统没有改用平台额度。", "在卷外设置中重新验证密钥", "切换回平台推演");
        }
        return new ModelRoute("USER_PREFERENCE", preference.provider(), preference.model(),
                preference.baseUrl(), "USER", preference.ciphertext());
    }

    public record ModelRoute(String taskType, String providerCode, String modelCode, String baseUrl,
                             String credentialSource, String credentialCiphertext) {
    }

    private record Preference(String source, String provider, String model, String baseUrl,
                              boolean modelActive, boolean providerActive, String ciphertext,
                              String credentialStatus) {
    }
}

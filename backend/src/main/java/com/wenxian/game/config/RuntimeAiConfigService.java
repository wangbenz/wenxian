package com.wenxian.game.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

@Service
public class RuntimeAiConfigService {
    private final JdbcTemplate jdbc;
    private final String fallbackApiKey;
    private final String fallbackModel;
    private final String masterKey;

    public RuntimeAiConfigService(JdbcTemplate jdbc,
                                  @Value("${deepseek.api-key:}") String fallbackApiKey,
                                  @Value("${deepseek.model:deepseek-v4-pro}") String fallbackModel,
                                  @Value("${game.ai-config-master-key:wenxian-local-config-key-change-me}") String masterKey) {
        this.jdbc = jdbc;
        this.fallbackApiKey = fallbackApiKey == null ? "" : fallbackApiKey.strip();
        this.fallbackModel = fallbackModel;
        this.masterKey = masterKey;
    }

    @PostConstruct
    void seedModels() {
        Instant now = Instant.now();
        seed("deepseek-v4-pro", "V4 Pro", "deepseek-v4-pro".equals(fallbackModel), now);
        seed("deepseek-v4-flash", "V4 Flash", "deepseek-v4-flash".equals(fallbackModel), now);
        Integer active = jdbc.queryForObject("select count(*) from ai_model_config where active = true", Integer.class);
        if (active == null || active == 0) {
            jdbc.update("update ai_model_config set active = (model_code = ?)", fallbackModel);
        }
    }

    private void seed(String code, String name, boolean active, Instant now) {
        jdbc.update("""
                insert ignore into ai_model_config(model_code, display_name, secret_ciphertext, active, updated_at)
                values (?, ?, null, ?, ?)
                """, code, name, active, Timestamp.from(now));
    }

    public RuntimeConfig current() {
        RuntimeConfig config = jdbc.query("""
                select model_code, display_name, secret_ciphertext
                from ai_model_config where active = true limit 1
                """, (row, index) -> {
            String encrypted = row.getString("secret_ciphertext");
            String key = encrypted == null || encrypted.isBlank()
                    ? fallbackApiKey : SecretCipher.decrypt(encrypted, masterKey);
            return new RuntimeConfig(row.getString("model_code"), row.getString("display_name"), key);
        }).stream().findFirst().orElse(null);
        return config != null ? config : new RuntimeConfig(fallbackModel, fallbackModel, fallbackApiKey);
    }

    public record RuntimeConfig(String model, String displayName, String apiKey) {}
}

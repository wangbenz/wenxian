package com.wenxian.game.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 保护生产 profile 不执行 schema.sql，并强制外部提供敏感配置。 */
class ProductionConfigurationContractTest {
    @Test
    void productionProfileDisablesSchemaInitializationAndLocalSecurityDefaults() {
        Properties properties = properties();

        assertEquals("never", properties.getProperty("spring.sql.init.mode"));
        assertEquals("true", properties.getProperty("game.auth.secure-cookie"));
        assertEquals("${GAME_DATABASE_URL}", properties.getProperty("spring.datasource.url"));
        assertEquals("${GAME_DATABASE_USERNAME}", properties.getProperty("spring.datasource.username"));
        assertEquals("${GAME_DATABASE_PASSWORD}", properties.getProperty("spring.datasource.password"));
        assertEquals("${WENXIAN_CORS_ALLOWED_ORIGIN_PATTERNS}",
                properties.getProperty("game.cors.allowed-origin-patterns"));
        assertEquals("${WENXIAN_INVITE_CODE:}", properties.getProperty("game.invite.bootstrap-code"));
        assertEquals("${WENXIAN_IP_HASH_PEPPER}", properties.getProperty("game.auth.ip-hash-pepper"));
        assertEquals("${WENXIAN_AI_CONFIG_MASTER_KEY}", properties.getProperty("game.ai-config-master-key"));
        assertEquals("${WENXIAN_STORY_CORRECTION_TOKEN_SECRET}",
                properties.getProperty("game.story-correction-token-secret"));
        assertEquals("${WENXIAN_ACCOUNT_POLICY_EVALUATION_ENABLED:false}",
                properties.getProperty("game.account-retention.policy-evaluation-enabled"));
        assertEquals("${WENXIAN_ACCOUNT_NOTIFICATION_DELIVERY_ENABLED:false}",
                properties.getProperty("game.account-retention.notification-delivery-enabled"));
        assertEquals("${WENXIAN_ACCOUNT_AUTOMATIC_DELETION_ENABLED:false}",
                properties.getProperty("game.account-retention.automatic-deletion-enabled"));
        assertEquals("${WENXIAN_ACCOUNT_SUBJECT_HASH_PEPPER}",
                properties.getProperty("game.account-retention.subject-hash-pepper"));
    }

    private Properties properties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yml"));
        Properties properties = yaml.getObject();
        assertNotNull(properties, "生产 profile 必须可以解析");
        return properties;
    }
}

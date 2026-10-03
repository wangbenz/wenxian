package com.wenxian.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 保护管理后端生产 profile 不执行 schema.sql，并强制外部提供共享密钥和连接。 */
class ProductionConfigurationContractTest {
    @Test
    void productionProfileDisablesSchemaInitializationAndLocalSecurityDefaults() {
        Properties properties = properties();

        assertEquals("never", properties.getProperty("spring.sql.init.mode"));
        assertEquals("${ADMIN_DATABASE_URL}", properties.getProperty("spring.datasource.url"));
        assertEquals("${ADMIN_DATABASE_USERNAME}", properties.getProperty("spring.datasource.username"));
        assertEquals("${ADMIN_DATABASE_PASSWORD}", properties.getProperty("spring.datasource.password"));
        assertEquals("${WENXIAN_AI_CONFIG_MASTER_KEY}", properties.getProperty("wenxian.ai-config-master-key"));
        assertEquals("${WENXIAN_GAME_SERVICE_BASE_URL}",
                properties.getProperty("wenxian.game-service.base-url"));
    }

    private Properties properties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yml"));
        Properties properties = yaml.getObject();
        assertNotNull(properties, "生产 profile 必须可以解析");
        return properties;
    }
}

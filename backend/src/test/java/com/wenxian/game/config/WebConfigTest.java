package com.wenxian.game.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class WebConfigTest {
    @Test
    void acceptsCommaSeparatedDeploymentOrigins() {
        WebConfig config = new WebConfig(
                "http://127.0.0.1:*, http://116.198.232.119:*, https://wenxian.example.com");

        assertDoesNotThrow(() -> config.addCorsMappings(new CorsRegistry()));
    }
}

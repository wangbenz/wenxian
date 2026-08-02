package com.wenxian.v2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wenxian.v2")
public record V2Properties(
        String engineVersion,
        String contentVersion,
        int maxActiveSaves,
        int sessionIdleHours
) {
    public V2Properties {
        engineVersion = nonBlank(engineVersion, "0.2.0");
        contentVersion = nonBlank(contentVersion, "qingqi-0.2.0");
        maxActiveSaves = Math.max(1, Math.min(maxActiveSaves, 8));
        sessionIdleHours = Math.max(1, Math.min(sessionIdleHours, 168));
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}

package com.wenxian.v2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wenxian.v2.ai")
public record AiProperties(
        boolean enabled,
        String platformApiKey,
        String credentialMasterKey,
        int timeoutSeconds,
        int maxRetries
) {
    public AiProperties {
        platformApiKey = platformApiKey == null ? "" : platformApiKey.strip();
        credentialMasterKey = credentialMasterKey == null ? "" : credentialMasterKey.strip();
        timeoutSeconds = Math.max(5, Math.min(timeoutSeconds, 180));
        maxRetries = Math.max(0, Math.min(maxRetries, 2));
    }

    public boolean platformAvailable() {
        return enabled && !platformApiKey.isBlank();
    }

    public boolean credentialEncryptionAvailable() {
        return !credentialMasterKey.isBlank();
    }
}

package com.wenxian.game.story.application;

import com.wenxian.game.analytics.AnalyticsEventPublisher;
import com.wenxian.game.analytics.AnalyticsEventType;
import com.wenxian.game.story.model.GenerationApiModels.CredentialRequest;
import com.wenxian.game.story.model.GenerationApiModels.SettingsRequest;
import com.wenxian.game.story.model.GenerationApiModels.SettingsResponse;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * 推演来源设置的版本无关门面。
 *
 * <p>应用服务只编排稳定设置端口，绝不读取或返回明文密钥，也不依赖发布版本类型。</p>
 */
@Service
public class GenerationApplicationService {
    private final GenerationSettingsGateway settings;
    private final AnalyticsEventPublisher analytics;

    public GenerationApplicationService(GenerationSettingsGateway settings, AnalyticsEventPublisher analytics) {
        this.settings = settings;
        this.analytics = analytics;
    }

    public SettingsResponse settings(String userId) {
        return settings.settings(userId);
    }

    public SettingsResponse saveSettings(String userId, SettingsRequest request) {
        return settings.saveSettings(userId, request);
    }

    public SettingsResponse saveCredential(String userId, String provider, CredentialRequest request) {
        String providerCode = normalizeProvider(provider);
        SettingsResponse response = settings.saveCredential(userId, providerCode, request);
        // 同一玩家和供应商只记录首次成功接入，用稳定幂等键避免浏览器重试放大转化人数。
        analytics.publish("BYOK_CREDENTIAL_CONNECTED:" + userId + ":" + providerCode,
                AnalyticsEventType.BYOK_CREDENTIAL_CONNECTED, userId, "", "",
                Map.of("providerCode", providerCode));
        return response;
    }

    public SettingsResponse deleteCredential(String userId, String provider) {
        String providerCode = normalizeProvider(provider);
        SettingsResponse response = settings.deleteCredential(userId, providerCode);
        // 删除后的统计发布不能为了取版本再次读取凭证，避免可观测性故障阻断玩家删除操作。
        analytics.publish("BYOK_CREDENTIAL_DELETED:" + userId + ":" + providerCode,
                AnalyticsEventType.BYOK_CREDENTIAL_DELETED, userId, "", "",
                Map.of("providerCode", providerCode));
        return response;
    }

    private String normalizeProvider(String provider) {
        return provider == null ? "" : provider.strip().toUpperCase(Locale.ROOT);
    }
}

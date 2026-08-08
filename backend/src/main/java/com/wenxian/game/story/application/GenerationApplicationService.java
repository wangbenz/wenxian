package com.wenxian.game.story.application;

import com.wenxian.game.story.model.GenerationApiModels.CredentialRequest;
import com.wenxian.game.story.model.GenerationApiModels.PersonalModel;
import com.wenxian.game.story.model.GenerationApiModels.PersonalProvider;
import com.wenxian.game.story.model.GenerationApiModels.PlatformAccess;
import com.wenxian.game.story.model.GenerationApiModels.SettingsRequest;
import com.wenxian.game.story.model.GenerationApiModels.SettingsResponse;
import com.wenxian.game.v014.model.V014ApiModels.GenerationSettings;
import com.wenxian.game.v014.service.V014ModelPreferenceService;
import org.springframework.stereotype.Service;

/**
 * 推演来源设置的版本无关门面。
 *
 * <p>当前委托兼容服务完成密钥加密、验证和事务写入；本服务只转换稳定契约，绝不读取或返回明文密钥。</p>
 */
@Service
public class GenerationApplicationService {
    private final V014ModelPreferenceService preferences;

    public GenerationApplicationService(V014ModelPreferenceService preferences) {
        this.preferences = preferences;
    }

    public SettingsResponse settings(String userId) {
        return map(preferences.settings(userId));
    }

    public SettingsResponse saveSettings(String userId, SettingsRequest request) {
        return map(preferences.saveSettings(userId, request.mode(), request.provider(), request.model()));
    }

    public SettingsResponse saveCredential(String userId, String provider, CredentialRequest request) {
        return map(preferences.saveCredential(userId, provider, request.model(), request.apiKey()));
    }

    public SettingsResponse deleteCredential(String userId, String provider) {
        return map(preferences.deleteCredential(userId, provider));
    }

    private SettingsResponse map(GenerationSettings source) {
        PlatformAccess platform = new PlatformAccess(source.platform().turnLimit(), source.platform().usedTurns(),
                source.platform().remainingTurns(), source.platform().available());
        return new SettingsResponse(source.mode(), platform, source.personalProviders().stream()
                .map(provider -> new PersonalProvider(provider.code(), provider.name(),
                        provider.credentialConfigured(), provider.credentialStatus(), provider.maskedSuffix(),
                        provider.lastVerifiedAt(), provider.models().stream()
                                .map(model -> new PersonalModel(model.code(), model.name())).toList()))
                .toList(), source.selectedPersonalProvider(), source.selectedPersonalModel());
    }
}

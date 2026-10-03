package com.wenxian.game.story.application;

import com.wenxian.game.story.model.GenerationApiModels.CredentialRequest;
import com.wenxian.game.story.model.GenerationApiModels.SettingsRequest;
import com.wenxian.game.story.model.GenerationApiModels.SettingsResponse;

/**
 * 推演设置的应用端口。
 *
 * <p>实现负责目录读取、密钥验证、加密存储和偏好事务；稳定应用层只接触玩家可见的脱敏契约，
 * 不得依赖历史版本模型或取得明文密钥。</p>
 */
public interface GenerationSettingsGateway {
    SettingsResponse settings(String userId);

    SettingsResponse saveSettings(String userId, SettingsRequest request);

    SettingsResponse saveCredential(String userId, String provider, CredentialRequest request);

    SettingsResponse deleteCredential(String userId, String provider);
}

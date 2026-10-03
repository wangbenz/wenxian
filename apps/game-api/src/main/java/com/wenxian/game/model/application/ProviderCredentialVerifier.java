package com.wenxian.game.model.application;

import com.wenxian.game.model.domain.ModelDefinition;

/**
 * 验证玩家提供的模型密钥是否可以调用所选模型。
 *
 * <p>验证只发生在保存密钥之前，不写故事状态、不扣除平台回合，也不得在异常中回显明文密钥。</p>
 */
public interface ProviderCredentialVerifier {
    void verify(ModelDefinition model, String apiKey);
}

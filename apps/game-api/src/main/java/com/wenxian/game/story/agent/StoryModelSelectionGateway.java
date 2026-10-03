package com.wenxian.game.story.agent;

/**
 * 玩家当前推演来源的脱敏查询与失效标记端口。
 *
 * <p>该端口不解析模型地址、不解密密钥，也不参与任务路由。普通回合的真实任务路由由
 * {@code ModelTaskRouter} 冻结；这里仅服务玩家响应展示和自备密钥鉴权失败后的状态更新。</p>
 */
public interface StoryModelSelectionGateway {
    /** 读取玩家当前选择的推演来源，不解密也不返回密钥。 */
    StoryModelSelection currentSelection(String userId);

    /** 自备密钥被供应商拒绝时标记为不可用，避免后续回合继续使用同一失效凭据。 */
    void markCredentialInvalid(String userId, String provider, String reason);

    /** 玩家可见响应所需的脱敏模型选择，不包含地址或密钥。 */
    record StoryModelSelection(String provider, String model, String credentialSource) {
        public boolean personal() {
            return "USER".equalsIgnoreCase(credentialSource);
        }
    }
}

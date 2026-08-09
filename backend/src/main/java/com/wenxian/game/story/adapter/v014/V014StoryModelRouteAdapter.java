package com.wenxian.game.story.adapter.v014;

import com.wenxian.game.story.agent.StoryModelRoute;
import com.wenxian.game.story.agent.StoryModelRouteResolver;
import com.wenxian.game.v014.service.V014ModelPreferenceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 将现有模型偏好服务适配为中性故事模型路由。
 *
 * <p>该防腐层只负责字段转换，不决定 Prompt 和玩法；待模型配置迁入中性服务后可以替换本实现，
 * 上层修正与故事应用服务无需变更。</p>
 */
@Component
public class V014StoryModelRouteAdapter implements StoryModelRouteResolver {
    private final V014ModelPreferenceService preferences;
    private final String legacyBaseUrl;

    public V014StoryModelRouteAdapter(
            V014ModelPreferenceService preferences,
            @Value("${deepseek.base-url:https://api.deepseek.com}") String legacyBaseUrl) {
        this.preferences = preferences;
        this.legacyBaseUrl = legacyBaseUrl;
    }

    @Override
    public StoryModelRoute resolve(String userId) {
        V014ModelPreferenceService.ResolvedRoute route = preferences.resolve(userId, legacyBaseUrl);
        return new StoryModelRoute(route.baseUrl(), route.apiKey(), route.provider(), route.adapterType(),
                route.model(), route.credentialSource());
    }
}

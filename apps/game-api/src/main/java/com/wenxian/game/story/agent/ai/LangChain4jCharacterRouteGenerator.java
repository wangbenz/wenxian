package com.wenxian.game.story.agent.ai;

import com.wenxian.game.model.application.ModelInvocationService;
import com.wenxian.game.model.domain.ModelRouteRequest;
import com.wenxian.game.model.domain.ModelTask;
import org.springframework.stereotype.Component;

/** 使用同一创角任务路由生成推荐或最终开场草案；此处不创建命卷、不新增正文调用。 */
@Component
public class LangChain4jCharacterRouteGenerator implements CharacterRouteGenerator {
    private final ModelInvocationService invocations;
    private final StoryAiServiceRegistry services;

    public LangChain4jCharacterRouteGenerator(ModelInvocationService invocations,
                                              StoryAiServiceRegistry services) {
        this.invocations = invocations;
        this.services = services;
    }

    @Override
    public CharacterCreationAi.CharacterRouteDraft generate(String userId, String requestId, String context) {
        return invocations.invoke(new ModelRouteRequest(ModelTask.CHARACTER_CREATION, requestId, userId, false),
                services::characterCreation, service -> service.generate(context)).content();
    }
}

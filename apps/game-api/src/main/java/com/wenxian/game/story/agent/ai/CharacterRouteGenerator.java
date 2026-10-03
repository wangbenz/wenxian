package com.wenxian.game.story.agent.ai;

/** 角色路线模型端口；返回内容仍需应用服务补齐稳定 ID 并校验。 */
public interface CharacterRouteGenerator {
    CharacterCreationAi.CharacterRouteDraft generate(String userId, String requestId, String context);
}

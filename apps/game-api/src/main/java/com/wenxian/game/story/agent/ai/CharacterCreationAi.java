package com.wenxian.game.story.agent.ai;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/** 角色路线候选生成服务；仅生成草案，不写命卷。 */
public interface CharacterCreationAi {
    @UserMessage("{{context}}")
    Result<CharacterRouteDraft> generate(@V("context") String context);

    record CharacterRouteDraft(List<RouteDraft> routes) {}

    record RouteDraft(String title, String origin, String opening,
                      List<String> establishedFacts, List<String> openHooks,
                      List<StartingEntityDraft> startingEntities, OpeningSceneDraft scene) {}

    /** 开篇与推荐行动在同一次创角生成中提出，玩家确认前不能写入命卷。 */
    record OpeningSceneDraft(String title, String narrative, List<OpeningChoiceDraft> choices) {}

    record OpeningChoiceDraft(String title, String description) {}

    /** 角色路线中的实体只是语义草案，不允许携带数据库 ID 或所有权技术字段。 */
    record StartingEntityDraft(String entityType, String name, String summary) {}
}

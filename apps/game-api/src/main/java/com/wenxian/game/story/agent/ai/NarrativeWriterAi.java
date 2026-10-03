package com.wenxian.game.story.agent.ai;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/** 正文演绎服务不取得事实写权限，只描述已经接受的回合结果。 */
public interface NarrativeWriterAi {
    @UserMessage("{{context}}")
    Result<NarrativeDraft> write(@V("context") String context);

    record NarrativeDraft(String title, String narrative, List<ChoiceDraft> choices, String summary) {}

    record ChoiceDraft(String title, String description) {}
}

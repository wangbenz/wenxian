package com.wenxian.game.story.agent.ai;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** 只修复无法读取的结构，不改变原任务语义。 */
public interface FormatRepairAi {
    @UserMessage("{{context}}")
    Result<RepairedStructure> repair(@V("context") String context);

    record RepairedStructure(String json) {}
}

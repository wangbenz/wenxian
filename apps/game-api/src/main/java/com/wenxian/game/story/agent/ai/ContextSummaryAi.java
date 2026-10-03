package com.wenxian.game.story.agent.ai;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/** 压缩已提交历史；摘要不是人物生死、物品或境界的权威来源。 */
public interface ContextSummaryAi {
    @UserMessage("{{context}}")
    Result<ContextSummaryDraft> summarize(@V("context") String context);

    record ContextSummaryDraft(String summary, List<String> unresolvedThreads, List<String> recentDecisions) {}
}

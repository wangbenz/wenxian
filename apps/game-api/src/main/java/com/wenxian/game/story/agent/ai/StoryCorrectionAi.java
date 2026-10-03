package com.wenxian.game.story.agent.ai;

import com.wenxian.game.story.domain.StoryCorrectionProposal;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** 把玩家的命卷修正说明解释为待校验提案；不写事实、不推进时间。 */
public interface StoryCorrectionAi {
    @UserMessage("{{context}}")
    Result<StoryCorrectionProposal> propose(@V("context") String context);
}

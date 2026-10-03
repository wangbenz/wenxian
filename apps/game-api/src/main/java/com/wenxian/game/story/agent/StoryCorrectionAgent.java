package com.wenxian.game.story.agent;

import com.wenxian.game.story.domain.StoryCorrectionProposal;

/**
 * 将玩家的修正说明解释为结构化提案，但不应用提案。
 *
 * <p>实现只能读取已经分层的命卷上下文。返回值仍是不可信模型输出，必须经过领域校验、签名预览和
 * 玩家确认，之后才允许交给 Reducer。</p>
 */
public interface StoryCorrectionAgent {
    Generated propose(String userId, String requestId, StoryPromptContext context, String instruction);

    record Generated(StoryCorrectionProposal proposal) {}
}

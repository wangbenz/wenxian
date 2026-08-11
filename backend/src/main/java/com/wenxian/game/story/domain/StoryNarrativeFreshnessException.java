package com.wenxian.game.story.domain;

/**
 * 表示候选正文没有形成独立的新回合内容。
 *
 * <p>该异常只描述文本重复，不代表物品、人物关系或世界状态存在业务冲突。应用层据此选择“从当前
 * 行动重新生成”，避免把整份重复正文再次交给一般语义修复。</p>
 */
public final class StoryNarrativeFreshnessException extends IllegalArgumentException {
    public StoryNarrativeFreshnessException(String message) {
        super(message);
    }
}

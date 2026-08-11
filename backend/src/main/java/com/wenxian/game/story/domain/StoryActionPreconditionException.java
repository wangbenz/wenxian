package com.wenxian.game.story.domain;

/** 玩家行动建立在与当前命卷不一致的前提上；该异常发生在剧情生成前，不应消耗回合。 */
public final class StoryActionPreconditionException extends IllegalArgumentException {
    private final String correction;

    public StoryActionPreconditionException(String reason, String correction) {
        super(reason == null || reason.isBlank() ? "本次行动的事实前提与当前命卷不一致" : reason.strip());
        this.correction = correction == null ? "" : correction.strip();
    }

    public String correction() {
        return correction;
    }
}

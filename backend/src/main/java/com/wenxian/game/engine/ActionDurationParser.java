package com.wenxian.game.engine;

import com.wenxian.game.story.domain.ExplicitDurationParser;

/** 旧版行动时长入口；实现已统一委托给中立故事领域，避免两套时间换算规则继续分叉。 */
public final class ActionDurationParser {
    private ActionDurationParser() {}

    public static Integer parseMinutes(String action) {
        return parseMinutes(action, 0);
    }

    public static Integer parseMinutes(String action, int currentMinute) {
        return ExplicitDurationParser.parseMinutes(action, currentMinute);
    }
}

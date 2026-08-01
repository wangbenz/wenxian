package com.wenxian.game.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ActionDurationParserTest {
    @Test
    void parsesExplicitCultivationAndTravelDurations() {
        assertEquals(3 * 360 * 1440, ActionDurationParser.parseMinutes("闭关三年，稳固境界"));
        assertEquals(30 * 1440, ActionDurationParser.parseMinutes("赶路一个月去北荒"));
        assertEquals(60, ActionDurationParser.parseMinutes("休息半个时辰"));
        assertEquals(90, ActionDurationParser.parseMinutes("等待六个刻钟"));
        assertEquals(1100, ActionDurationParser.parseMinutes("在坊市外休整一夜，天亮后入城", 11 * 60 + 40));
        assertEquals(500, ActionDurationParser.parseMinutes("留宿到明日天亮", 21 * 60 + 40));
    }

    @Test
    void ignoresNumbersThatAreNotActionDurations() {
        assertNull(ActionDurationParser.parseMinutes("我今年三十岁，去问问掌柜"));
        assertNull(ActionDurationParser.parseMinutes("购买三个月饼"));
    }
}

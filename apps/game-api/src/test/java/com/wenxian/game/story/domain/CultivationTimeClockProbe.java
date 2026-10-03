package com.wenxian.game.story.domain;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 新时间取舍方案与现行语义时钟的兼容性探针，直接执行正式解析器和时钟。
 *
 * <p>本类刻画当前差异，不声明旧时段制已经支持精确半日玩法，也不保护该差异长期不变。
 * 没有 Test 后缀，仅通过 -Dtest=CultivationTimeClockProbe 显式运行；没有模型、数据库或网络调用。</p>
 */
@Tag("gameplay-gap-probe")
class CultivationTimeClockProbe {
    @Test
    void threeExplicitDaysAdvanceThreeCalendarDays() {
        var start = StoryWorldTime.opening();
        var duration = SemanticDurationParser.parse("闭关修炼三天").orElseThrow();
        assertEquals(SemanticDuration.Unit.DAY, duration.unit());
        assertEquals(3, duration.value());
        assertEquals(start.plusDays(3), start.advance(duration));
        assertEquals(4, start.advance(duration).day());
    }

    @Test
    void twoParsedHalfDaysDoNotEqualOneDayInTheCurrentClock() {
        var start = StoryWorldTime.opening();
        var halfDay = SemanticDurationParser.parse("赶路半日").orElseThrow();
        var split = start.advance(halfDay).advance(halfDay);
        var whole = start.advance(SemanticDurationParser.parse("赶路一天").orElseThrow());

        assertEquals(SemanticDuration.Unit.PHASE, halfDay.unit());
        assertEquals(4, halfDay.value());
        assertEquals(7, StoryWorldTime.Phase.values().length);
        assertEquals(new StoryWorldTime(1, 2, StoryWorldTime.Phase.NOON), split);
        assertEquals(new StoryWorldTime(1, 2, StoryWorldTime.Phase.MORNING), whole);
        assertNotEquals(whole, split, "当前行为观测：图中的精确半日不能直接替换现有时段推进");
        assertEquals(1, split.toOrder() - whole.toOrder());
    }

    @Test
    void anActivityDescriptionDoesNotAutomaticallyLoadItsAuthoredHalfDayCost() {
        assertTrue(SemanticDurationParser.parse("使用搬运符完成重件托运").isEmpty(),
                "动作本身未声明时长；当前解析器不会查内容表中的半日成本");
    }
}

package com.wenxian.game.story.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryNarrativeFreshnessValidatorTest {
    private final StoryNarrativeFreshnessValidator validator = new StoryNarrativeFreshnessValidator();

    @Test
    void rejectsLongNarrativeThatOnlyChangesOpeningWords() {
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < 60; index++) {
            body.append("你沿着第").append(index).append("道锈纹继续查看断剑，仍没有发现新的灵机。");
        }
        String shared = body.toString();
        String previous = "夜色深沉。" + shared + "你停在门口等待下一步。";
        String candidate = "月上中天。" + shared + "你仍停在门口等待下一步。";

        StoryNarrativeFreshnessException error = assertThrows(StoryNarrativeFreshnessException.class,
                () -> validator.validate(previous, candidate));

        assertTrue(error.getMessage().contains("高度重复"));
        assertTrue(validator.containment(previous, candidate, 5) > 0.90d);
    }

    @Test
    void acceptsSameSceneWhenNewActionProducesDifferentContent() {
        String previous = "你在村尾门外逐寸观察断剑的锈迹与断口，没有发现新的灵机。".repeat(12);
        String candidate = "你向老妇说明来意，她认真听完后拒绝收徒，却愿意告诉你古战场的旧闻。".repeat(12);

        assertDoesNotThrow(() -> validator.validate(previous, candidate));
    }

    @Test
    void stillRejectsExactShortDuplicate() {
        StoryNarrativeFreshnessException error = assertThrows(StoryNarrativeFreshnessException.class,
                () -> validator.validate("你停在门外。", " 你停在门外。\n"));
        assertTrue(error.getMessage().contains("完全重复"));
    }

    @Test
    void rejectsReplayingAnEntireShortPreviousTurnBeforeTheNewAction() {
        String previous = "晨光透过薄雾洒在村路上，你站在矮屋门前凝神聚气，指尖浮现一缕淡青剑芒。"
                + "剑芒在晨光中短暂亮起，你随即收势，没有攻击任何人，也没有离开原地。";
        String candidate = previous + "随后你转向周山，询问他今日身体是否安好，听完回答便停下。";

        StoryNarrativeFreshnessException error = assertThrows(StoryNarrativeFreshnessException.class,
                () -> validator.validate(previous, candidate));

        assertTrue(error.getMessage().contains("开头重复执行"));
    }
}

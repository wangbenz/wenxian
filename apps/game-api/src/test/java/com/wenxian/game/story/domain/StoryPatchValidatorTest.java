package com.wenxian.game.story.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.wenxian.game.story.domain.StoryPatch.EntityOperation.CREATE;
import static com.wenxian.game.story.domain.StoryPatch.EntityOperation.UPDATE;
import static com.wenxian.game.story.domain.StoryPatch.TimeResolution.COMPLETE;
import static com.wenxian.game.story.domain.StoryPatch.TimeResolution.INTERRUPTED;
import static com.wenxian.game.story.domain.StoryPatch.WorldFactOperation.REVISE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryPatchValidatorTest {
    private final StoryPatchValidator validator = new StoryPatchValidator();

    @Test
    void acceptsStableReferencesUnknownQuantityAndAuditedWorldRevision() {
        StoryPatch patch = patch(
                time(SemanticDuration.Unit.SCENE, 1, COMPLETE, null),
                List.of(
                        new StoryPatch.EntityPatch(UPDATE, "ability-reflection", "ABILITY", null,
                                "仍可施展", null, null, null, null,
                                StoryPatch.AbilityState.MASTERED, null),
                        new StoryPatch.EntityPatch(CREATE, null, "ITEM", "一包散碎灵材",
                                "随身携带", "数量尚未清点", null,
                                new StoryPatch.QuantityValue(false, null), true, null, null)
                ),
                List.of(new StoryPatch.WorldFactPatch(REVISE, "world-fact-ascension",
                        "飞升一直存在，只是道路被少数势力隐瞒。", "玩家明确修订世界设定"))
        );

        assertDoesNotThrow(() -> validator.validate(patch));
    }

    @Test
    void rejectsNameOnlyUpdateAndInventedUnknownQuantity() {
        StoryPatch nameOnly = patch(time(SemanticDuration.Unit.SCENE, 1, COMPLETE, null),
                List.of(new StoryPatch.EntityPatch(UPDATE, null, "ABILITY", "照影诀",
                        "仍可施展", null, null, null, null,
                        StoryPatch.AbilityState.MASTERED, null)), List.of());
        IllegalArgumentException missingId = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(nameOnly));
        assertEquals("更新已有实体必须引用稳定ID", missingId.getMessage());

        StoryPatch invented = patch(time(SemanticDuration.Unit.SCENE, 1, COMPLETE, null),
                List.of(new StoryPatch.EntityPatch(CREATE, null, "ITEM", "未知灵材",
                        "随身携带", "尚未清点的灵材。", null, new StoryPatch.QuantityValue(false, 1),
                        true, null, null)), List.of());
        IllegalArgumentException quantity = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(invented));
        assertEquals("未知数量不能伪造具体值", quantity.getMessage());
    }

    @Test
    void rejectsNewEntityBeforePersistenceWhenRequiredDatabaseFactsAreMissing() {
        StoryPatch missingStatus = patch(time(SemanticDuration.Unit.SCENE, 1, COMPLETE, null),
                List.of(new StoryPatch.EntityPatch(CREATE, null, "LANDMARK", "周山矮屋",
                        null, "周山居住的矮屋。", null, null,
                        null, null, null)), List.of());
        IllegalArgumentException status = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(missingStatus));
        assertEquals("新实体必须包含当前状态", status.getMessage());

        StoryPatch missingSummary = patch(time(SemanticDuration.Unit.SCENE, 1, COMPLETE, null),
                List.of(new StoryPatch.EntityPatch(CREATE, null, "LANDMARK", "周山矮屋",
                        "屋舍稳固", null, null, null,
                        null, null, null)), List.of());
        IllegalArgumentException summary = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(missingSummary));
        assertEquals("新实体必须包含事实摘要", summary.getMessage());
    }

    @Test
    void requiresReasonForInterruptedTimeAndPlayerDecisionPrompt() {
        StoryPatch interrupted = patch(time(SemanticDuration.Unit.PHASE, 4, INTERRUPTED, null), List.of(), List.of());
        IllegalArgumentException reason = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(interrupted));
        assertEquals("行动被中断时必须说明原因", reason.getMessage());

        StoryPatch checkpoint = new StoryPatch("山门之问", "使者等待你的答复。",
                time(SemanticDuration.Unit.SCENE, 1, COMPLETE, null), null, List.of(), List.of(), List.of(), List.of(),
                new StoryPatch.DecisionCheckpoint(true, ""), List.of(), "");
        IllegalArgumentException prompt = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(checkpoint));
        assertEquals("重大决定检查点必须说明待玩家决定的内容", prompt.getMessage());
    }

    @Test
    void acceptsSemanticLongActionsWithoutMinuteComparison() {
        StoryPatch complete = patch(time(SemanticDuration.Unit.YEAR, 3, COMPLETE, null),
                List.of(), List.of());
        assertDoesNotThrow(() -> validator.validate(complete));

        StoryPatch interrupted = patch(time(SemanticDuration.Unit.MONTH, 3, INTERRUPTED,
                "闭关地灵脉突然枯竭"), List.of(), List.of());
        assertDoesNotThrow(() -> validator.validate(interrupted));
    }

    private StoryPatch patch(StoryPatch.TimePassage time, List<StoryPatch.EntityPatch> entities,
                             List<StoryPatch.WorldFactPatch> worldFacts) {
        return new StoryPatch("命卷新页", "你完成了本轮行动，并停在下一个需要选择的位置。", time,
                null, entities, worldFacts, List.of(), List.of(),
                new StoryPatch.DecisionCheckpoint(false, null), List.of(), "");
    }

    private StoryPatch.TimePassage time(SemanticDuration.Unit unit, int value,
                                        StoryPatch.TimeResolution resolution, String reason) {
        return new StoryPatch.TimePassage(new SemanticDuration(unit, value, ""), resolution, reason);
    }
}

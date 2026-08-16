package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.Conflict;
import com.wenxian.game.story.domain.StoryActionContract.EntityReference;
import com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoryCharacterReferenceEvidenceValidatorTest {
    private final StoryCharacterReferenceEvidenceValidator validator =
            new StoryCharacterReferenceEvidenceValidator();

    @Test
    void rejectsBindingSceneGenericNameToUnmentionedStableCharacter() {
        EntityState cultivator = character("character-cultivator", "柳姓老修士");
        StoryActionContract contract = contract(new EntityReference("老人", ReferenceResolution.EXISTING,
                cultivator.id(), "CHARACTER", cultivator.name(), "TARGET", null));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                projection(cultivator), contract, List.of(),
                List.of("静候老人答复", "卖画老人仍在摊前端详玉简")));
    }

    @Test
    void acceptsStableCharacterWhenCurrentEvidenceNamesThatIdentity() {
        EntityState cultivator = character("character-cultivator", "柳姓老修士");
        StoryActionContract contract = contract(new EntityReference("老修士", ReferenceResolution.EXISTING,
                cultivator.id(), "CHARACTER", cultivator.name(), "TARGET", null));

        assertDoesNotThrow(() -> validator.validate(projection(cultivator), contract, List.of(),
                List.of("返回柳姓老修士居所，请他继续指点")));
    }

    @Test
    void leavesSourceEstablishedSceneCharacterForPromotionOnChange() {
        StoryActionContract contract = contract(new EntityReference("卖画老人", ReferenceResolution.ESTABLISHED,
                null, "CHARACTER", "卖画老人", "TARGET", null,
                List.of("turn:27"), "seller"));

        assertDoesNotThrow(() -> validator.validate(projection(), contract, List.of(),
                List.of("卖画老人接过玉简端详")));
    }

    private StoryActionContract contract(EntityReference reference) {
        return new StoryActionContract("继续当前互动", false, List.of(reference), List.of(), List.of(), "",
                new Conflict(false, "", ""));
    }

    private EntityState character(String id, String name) {
        return new EntityState(id, "CHARACTER", name, "ACTIVE", "已确认人物", null,
                true, null, null, null, null);
    }

    private StoryProjection projection(EntityState... entities) {
        Map<String, EntityState> values = java.util.Arrays.stream(entities)
                .collect(java.util.stream.Collectors.toMap(EntityState::id, value -> value));
        return new StoryProjection(0, new CoreState("画摊前", "平静", "启灵", "问道"),
                values, Map.of(), Map.of(), List.of());
    }
}

package com.wenxian.game.story.model;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证统一剧情接口与 H5 输入边界保持一致，避免玩家在最后一步才遇到无意义的参数错误。 */
class StoryApiModelsValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsTheFullCharacterOriginLengthOfferedByTheH5() {
        var request = request("灵".repeat(160));

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsCharacterOriginBeyondThePublishedBoundary() {
        var request = request("灵".repeat(161));

        assertFalse(validator.validate(request).isEmpty());
    }

    private StoryApiModels.CreateSaveRequest request(String origin) {
        return new StoryApiModels.CreateSaveRequest(
                "XUANXU", null, "宁临渊", "灵族", "男", origin, null, "自由探索");
    }
}

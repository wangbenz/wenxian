package com.wenxian.game.story.model;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证统一剧情接口与 H5 输入边界保持一致，避免玩家在最后一步才遇到无意义的参数错误。 */
class StoryApiModelsValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsTheFullCharacterOriginLengthOfferedByTheH5() {
        var request = request("灵".repeat(240));

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsCharacterOriginBeyondThePublishedBoundary() {
        var request = request("灵".repeat(241));

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void acceptsCreationWithoutPlayerSubmittedRealmSystem() {
        var request = request("矿石化灵");

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void acceptsCompleteCreationSeedsAndRejectsUnknownMode() {
        var identity = request("来历").identity();
        assertTrue(validator.validate(new StoryApiModels.CharacterRouteRequest("XUANXU", 2, identity,
                "来".repeat(300), "偏".repeat(220), "FINALIZE")).isEmpty());
        assertFalse(validator.validate(new StoryApiModels.CharacterRouteRequest("XUANXU", 2, identity,
                "来历", "偏好", "AUTO_CREATE")).isEmpty());
    }

    @Test
    void permitsSixModelFactsPlusExplicitNonHumanIdentityAndValidatesSceneContents() {
        var original = request("古砚化灵，想看河流上游");
        var facts = new java.util.ArrayList<StoryApiModels.OriginFactSubmission>();
        for (int i = 0; i < 6; i++) facts.add(new StoryApiModels.OriginFactSubmission(
                "origin:fact:" + i, "PAST_EVENT", "此前已经发生的经历"));
        facts.add(new StoryApiModels.OriginFactSubmission("origin:fact:identity", "IDENTITY", "本体为古砚"));
        var origin = new StoryApiModels.CharacterOriginSubmission(original.origin().summary(), facts, List.of(), List.of());
        var scene = new StoryApiModels.OpeningSceneSubmission("渡口", "你看见渡口有人收帆。",
                List.of(new StoryApiModels.OpeningChoiceSubmission("问路", "向船工询问上游行程。")));
        var valid = new StoryApiModels.CreateSaveRequest("XUANXU", 2, original.identity(), origin,
                new StoryApiModels.OpeningSubmission(original.opening().regionId(), "渡口停船", scene),
                original.playPreference(), null);
        assertTrue(validator.validate(valid).isEmpty());
        var invalid = new StoryApiModels.OpeningSceneSubmission("渡口", " ", List.of());
        assertFalse(validator.validate(new StoryApiModels.OpeningSubmission("region:xuanxu:qingqi", "渡口", invalid)).isEmpty());
    }

    private StoryApiModels.CreateSaveRequest request(String origin) {
        return new StoryApiModels.CreateSaveRequest(
                "XUANXU", 2,
                new StoryApiModels.CharacterIdentitySubmission("宁临渊", "MALE", "race:xuanxu:spirit"),
                new StoryApiModels.CharacterOriginSubmission(origin, List.of(
                        new StoryApiModels.OriginFactSubmission("origin:fact:1", "IDENTITY", origin)),
                        List.of(), List.of()),
                new StoryApiModels.OpeningSubmission("region:xuanxu:qingqi", "刚刚在矿洞中醒来"),
                new StoryApiModels.PlayPreferenceSubmission(List.of("EXPLORATION"), "BALANCED", "BALANCED",
                        "LIGHT", ""), null);
    }
}

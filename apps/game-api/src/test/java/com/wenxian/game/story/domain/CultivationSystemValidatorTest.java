package com.wenxian.game.story.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CultivationSystemValidatorTest {
    private final CultivationSystemValidator validator = new CultivationSystemValidator();

    @Test
    void acceptsSixOrderedStagesWithUniqueAliasesAndAdjacentTransitions() {
        CultivationSystem system = validSystem();

        assertDoesNotThrow(() -> validator.validate(system));
    }

    @Test
    void rejectsTooFewStagesAndNonContinuousOrder() {
        CultivationSystem tooShort = copyWithStages(validSystem(), validSystem().stages().subList(0, 5));
        IllegalArgumentException stageCount = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(tooShort));
        assertEquals("境界序列必须包含六至十个大境界", stageCount.getMessage());

        List<CultivationSystem.Stage> stages = validSystem().stages().stream()
                .map(stage -> stage.order() == 4
                        ? new CultivationSystem.Stage(stage.stageId(), 7, stage.name(), stage.description(),
                        stage.aliases(), stage.allowedNextStageIds())
                        : stage)
                .toList();
        IllegalArgumentException order = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(copyWithStages(validSystem(), stages)));
        assertEquals("大境界顺序必须从一开始连续排列", order.getMessage());
    }

    @Test
    void rejectsDuplicateStageIdentityAndAmbiguousAlias() {
        List<CultivationSystem.Stage> duplicateId = validSystem().stages().stream()
                .map(stage -> stage.order() == 2
                        ? new CultivationSystem.Stage("stage-01", stage.order(), stage.name(), stage.description(),
                        stage.aliases(), stage.allowedNextStageIds())
                        : stage)
                .toList();
        IllegalArgumentException identity = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(copyWithStages(validSystem(), duplicateId)));
        assertEquals("大境界稳定ID不能重复", identity.getMessage());

        List<CultivationSystem.Stage> ambiguousAlias = validSystem().stages().stream()
                .map(stage -> stage.order() == 3
                        ? new CultivationSystem.Stage(stage.stageId(), stage.order(), stage.name(), stage.description(),
                        List.of("炼气"), stage.allowedNextStageIds())
                        : stage)
                .toList();
        IllegalArgumentException alias = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(copyWithStages(validSystem(), ambiguousAlias)));
        assertEquals("境界名称或别称不能指向多个阶段：炼气", alias.getMessage());
    }

    @Test
    void rejectsUnknownStartAndNonAdjacentNextStage() {
        CultivationSystem unknownStart = new CultivationSystem(validSystem().systemId(),
                validSystem().schemaVersion(), validSystem().name(), validSystem().summary(), validSystem().race(),
                "stage-unknown", validSystem().stages(), validSystem().confirmation());
        IllegalArgumentException start = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(unknownStart));
        assertEquals("建议起点必须属于当前主修体系", start.getMessage());

        List<CultivationSystem.Stage> skipped = validSystem().stages().stream()
                .map(stage -> stage.order() == 1
                        ? new CultivationSystem.Stage(stage.stageId(), stage.order(), stage.name(), stage.description(),
                        stage.aliases(), List.of("stage-03"))
                        : stage)
                .toList();
        IllegalArgumentException transition = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(copyWithStages(validSystem(), skipped)));
        assertEquals("首期主修体系只能配置相邻大境界为直接后继", transition.getMessage());
    }

    @Test
    void requiresCurrentStageToReferenceTheSameConfirmedSystem() {
        assertDoesNotThrow(() -> validator.validateState(validSystem(),
                new ProtagonistCultivation("cultivation-test", "stage-02", "已能稳定引气入体")));

        IllegalArgumentException otherSystem = assertThrows(IllegalArgumentException.class,
                () -> validator.validateState(validSystem(),
                        new ProtagonistCultivation("cultivation-other", "stage-02", "")));
        assertEquals("角色修行状态引用了其他体系", otherSystem.getMessage());

        IllegalArgumentException unknownStage = assertThrows(IllegalArgumentException.class,
                () -> validator.validateState(validSystem(),
                        new ProtagonistCultivation("cultivation-test", "stage-unknown", "")));
        assertEquals("当前境界必须属于角色主修体系", unknownStage.getMessage());
    }

    private CultivationSystem copyWithStages(CultivationSystem source, List<CultivationSystem.Stage> stages) {
        return new CultivationSystem(source.systemId(), source.schemaVersion(), source.name(), source.summary(),
                source.race(), source.recommendedStartStageId(), stages, source.confirmation());
    }

    static CultivationSystem validSystem() {
        return new CultivationSystem("cultivation-test", 1, "青冥炼真道", "借天地清气磨炼真形。", "人族",
                "stage-01", List.of(
                stage("stage-01", 1, "引气", List.of("感气"), "stage-02"),
                stage("stage-02", 2, "炼气", List.of("练气"), "stage-03"),
                stage("stage-03", 3, "筑基", List.of("道基"), "stage-04"),
                stage("stage-04", 4, "金丹", List.of("结丹"), "stage-05"),
                stage("stage-05", 5, "元婴", List.of("婴成"), "stage-06"),
                stage("stage-06", 6, "化神", List.of("神变"), null)
        ), new CultivationSystem.Confirmation(CultivationSystem.ConfirmationSource.PLAYER_CONFIRMED_RECOMMENDATION,
                Instant.parse("2026-08-20T12:00:00Z")));
    }

    private static CultivationSystem.Stage stage(String id, int order, String name, List<String> aliases,
                                                  String nextId) {
        return new CultivationSystem.Stage(id, order, name, name + "阶段的世界内含义。", aliases,
                nextId == null ? List.of() : List.of(nextId));
    }
}

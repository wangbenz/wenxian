package com.wenxian.game.story.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 判断一次结构化境界变化是否符合玩家已确认的主修体系。
 *
 * <p>本策略不决定突破是否成功。上游结果已经决定成功、部分成功或失败后，本类只判断目标阶段能否成为新的
 * 权威状态；被拒绝时保持当前阶段，供回合流程降级为可玩的失败或阶段性进展。</p>
 */
public final class CultivationTransitionPolicy {
    private final CultivationSystemValidator validator;

    public CultivationTransitionPolicy() {
        this(new CultivationSystemValidator());
    }

    CultivationTransitionPolicy(CultivationSystemValidator validator) {
        this.validator = validator;
    }

    /** 根据稳定 ID、显示名称或已确认别称解析同一阶段。 */
    public Optional<String> resolveStageId(CultivationSystem system, String reference) {
        validator.validate(system);
        if (reference == null || reference.isBlank()) return Optional.empty();
        Map<String, String> references = references(system);
        String direct = system.stages().stream()
                .filter(stage -> stage.stageId().equals(reference.strip()))
                .map(CultivationSystem.Stage::stageId)
                .findFirst().orElse(null);
        if (direct != null) return Optional.of(direct);
        return Optional.ofNullable(references.get(CultivationSystemValidator.normalizeReference(reference)));
    }

    /**
     * 评估结构化目标阶段。返回拒绝结果而不是抛出剧情异常，调用方据此移除非法境界变化并继续处理本轮结果。
     */
    public Decision evaluate(CultivationSystem system, String currentStageId, String targetReference) {
        validator.validate(system);
        CultivationSystem.Stage current = system.stages().stream()
                .filter(stage -> stage.stageId().equals(currentStageId))
                .findFirst().orElse(null);
        if (current == null) {
            return new Decision(Status.INVALID_CURRENT_STAGE, currentStageId, null, currentStageId,
                    "当前境界没有对应到已确认的主修体系，不能猜测迁移");
        }
        Optional<String> target = resolveStageId(system, targetReference);
        if (target.isEmpty()) {
            return new Decision(Status.UNKNOWN_TARGET, currentStageId, null, currentStageId,
                    "目标境界不属于当前主修体系");
        }
        String targetId = target.orElseThrow();
        if (current.stageId().equals(targetId)) {
            return new Decision(Status.UNCHANGED, currentStageId, targetId, currentStageId,
                    "角色仍处于当前大境界");
        }
        if (current.allowedNextStageIds().contains(targetId)) {
            return new Decision(Status.ALLOWED, currentStageId, targetId, targetId,
                    "目标是当前体系允许的直接后继");
        }
        return new Decision(Status.NOT_ALLOWED, currentStageId, targetId, currentStageId,
                "目标不是当前体系允许的直接后继");
    }

    private Map<String, String> references(CultivationSystem system) {
        Map<String, String> references = new LinkedHashMap<>();
        for (CultivationSystem.Stage stage : system.stages()) {
            references.put(CultivationSystemValidator.normalizeReference(stage.name()), stage.stageId());
            stage.aliases().forEach(alias -> references.put(
                    CultivationSystemValidator.normalizeReference(alias), stage.stageId()));
        }
        return references;
    }

    public enum Status {
        UNCHANGED,
        ALLOWED,
        UNKNOWN_TARGET,
        NOT_ALLOWED,
        INVALID_CURRENT_STAGE
    }

    /**
     * @param effectiveStageId 本次评估后应保留或采用的阶段；拒绝时始终等于当前阶段
     */
    public record Decision(Status status, String currentStageId, String targetStageId,
                           String effectiveStageId, String reason) {}
}

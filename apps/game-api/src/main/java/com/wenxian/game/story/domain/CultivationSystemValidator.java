package com.wenxian.game.story.domain;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 校验世界包投影到命卷后的公共境界序列及当前阶段。
 *
 * <p>本类只保护稳定 ID、顺序、别称唯一性和直接后继等通用不变量，不校验世界内容是否“像修仙”，
 * 世界包内容本身由 {@link WorldPackage} 负责校验。</p>
 */
public final class CultivationSystemValidator {
    private static final int MIN_STAGES = 6;
    private static final int MAX_STAGES = 10;
    private static final Pattern STABLE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{2,79}");

    /** 校验完整的已确认体系；失败时不应写入命卷。 */
    public void validate(CultivationSystem system) {
        if (system == null) throw new IllegalArgumentException("主修体系不能为空");
        validateStructure(system.systemId(), system.schemaVersion(), system.name(), system.summary(), system.race(),
                system.recommendedStartStageId(), system.stages());
        validateConfirmation(system.confirmation());
    }

    private void validateStructure(String systemId, int schemaVersion, String name, String summary, String race,
                                   String recommendedStartStageId, List<CultivationSystem.Stage> stages) {
        stableId(systemId, "主修体系稳定ID格式不正确");
        if (schemaVersion < 1) throw new IllegalArgumentException("主修体系结构版本必须为正数");
        required(name, "主修体系名称不能为空");
        required(summary, "主修体系说明不能为空");
        required(race, "主修体系缺少角色种族上下文");
        if (stages.size() < MIN_STAGES || stages.size() > MAX_STAGES) {
            throw new IllegalArgumentException("境界序列必须包含六至十个大境界");
        }

        Map<String, CultivationSystem.Stage> byId = new HashMap<>();
        Map<String, String> referenceOwners = new HashMap<>();
        Set<Integer> orders = new HashSet<>();
        for (CultivationSystem.Stage stage : stages) {
            validateStage(stage, byId, referenceOwners, orders);
        }
        for (int expected = 1; expected <= stages.size(); expected++) {
            if (!orders.contains(expected)) throw new IllegalArgumentException("大境界顺序必须从一开始连续排列");
        }
        if (!byId.containsKey(recommendedStartStageId)) {
            throw new IllegalArgumentException("建议起点必须属于当前主修体系");
        }
        validateAdjacentTransitions(stages, byId);
    }

    /** 校验角色当前阶段确实属于已经确认的同一体系。 */
    public void validateState(CultivationSystem system, ProtagonistCultivation state) {
        validate(system);
        if (state == null) throw new IllegalArgumentException("角色修行状态不能为空");
        if (!system.systemId().equals(state.systemId())) throw new IllegalArgumentException("角色修行状态引用了其他体系");
        boolean currentExists = system.stages().stream()
                .anyMatch(stage -> stage.stageId().equals(state.currentStageId()));
        if (!currentExists) throw new IllegalArgumentException("当前境界必须属于角色主修体系");
    }

    static String normalizeReference(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    private void validateStage(CultivationSystem.Stage stage,
                               Map<String, CultivationSystem.Stage> byId,
                               Map<String, String> referenceOwners,
                               Set<Integer> orders) {
        if (stage == null) throw new IllegalArgumentException("大境界不能为空");
        stableId(stage.stageId(), "大境界稳定ID格式不正确");
        if (byId.putIfAbsent(stage.stageId(), stage) != null) {
            throw new IllegalArgumentException("大境界稳定ID不能重复");
        }
        if (stage.order() < 1 || !orders.add(stage.order())) {
            throw new IllegalArgumentException("大境界顺序必须从一开始连续排列");
        }
        required(stage.name(), "大境界名称不能为空");
        required(stage.description(), "大境界说明不能为空");
        registerReference(referenceOwners, stage.name(), stage.stageId());
        Set<String> ownAliases = new HashSet<>();
        for (String alias : stage.aliases()) {
            required(alias, "大境界别称不能为空");
            String normalized = normalizeReference(alias);
            if (!ownAliases.add(normalized)) throw new IllegalArgumentException("同一大境界不能重复配置别称");
            registerReference(referenceOwners, alias, stage.stageId());
        }
        for (String nextId : stage.allowedNextStageIds()) {
            stableId(nextId, "直接后继境界ID格式不正确");
        }
    }

    private void registerReference(Map<String, String> owners, String reference, String stageId) {
        String normalized = normalizeReference(reference);
        String previous = owners.putIfAbsent(normalized, stageId);
        if (previous != null && !previous.equals(stageId)) {
            throw new IllegalArgumentException("境界名称或别称不能指向多个阶段：" + reference.strip());
        }
    }

    private void validateAdjacentTransitions(List<CultivationSystem.Stage> stages,
                                             Map<String, CultivationSystem.Stage> byId) {
        List<CultivationSystem.Stage> ordered = stages.stream()
                .sorted(java.util.Comparator.comparingInt(CultivationSystem.Stage::order))
                .toList();
        for (int index = 0; index < ordered.size(); index++) {
            CultivationSystem.Stage stage = ordered.get(index);
            List<String> next = stage.allowedNextStageIds();
            if (next.stream().anyMatch(id -> !byId.containsKey(id))) {
                throw new IllegalArgumentException("直接后继境界必须属于当前主修体系");
            }
            if (index == ordered.size() - 1) {
                if (!next.isEmpty()) throw new IllegalArgumentException("最高大境界不能配置直接后继");
            } else if (!next.equals(List.of(ordered.get(index + 1).stageId()))) {
                throw new IllegalArgumentException("首期主修体系只能配置相邻大境界为直接后继");
            }
        }
    }

    private void validateConfirmation(CultivationSystem.Confirmation confirmation) {
        if (confirmation == null || confirmation.source() == null || confirmation.confirmedAt() == null) {
            throw new IllegalArgumentException("主修体系必须包含玩家确认记录");
        }
    }

    private void stableId(String value, String message) {
        if (value == null || !STABLE_ID.matcher(value.strip()).matches()) {
            throw new IllegalArgumentException(message);
        }
    }

    private void required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
    }
}

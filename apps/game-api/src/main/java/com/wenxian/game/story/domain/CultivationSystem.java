package com.wenxian.game.story.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一份由世界包固定并投影到单个种族的境界序列。
 *
 * <p>该对象只描述境界身份、显示名称和允许的直接后继，不承担战力、胜率、修炼耗时或突破概率计算。
 * 普通回合引用 {@link Stage#stageId()}，不能以可能变化的显示名称作为状态身份。</p>
 */
public record CultivationSystem(
        String systemId,
        int schemaVersion,
        String name,
        String summary,
        String race,
        String recommendedStartStageId,
        List<Stage> stages,
        Confirmation confirmation
) {
    public CultivationSystem {
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    /** 单个有序大境界；别称只用于解析，不能产生新的境界身份。 */
    public record Stage(
            String stageId,
            int order,
            String name,
            String description,
            List<String> aliases,
            List<String> allowedNextStageIds
    ) {
        public Stage {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            allowedNextStageIds = allowedNextStageIds == null ? List.of() : List.copyOf(allowedNextStageIds);
        }
    }

    /** 境界序列的权威来源和命卷绑定时间；模型没有确认权。 */
    public record Confirmation(ConfirmationSource source, Instant confirmedAt) {}

    public enum ConfirmationSource {
        WORLD_PACKAGE_FIXED,
        PLAYER_CONFIRMED_CREATION,
        PLAYER_CONFIRMED_RECOMMENDATION,
        PLAYER_CONFIRMED_EDIT
    }
}

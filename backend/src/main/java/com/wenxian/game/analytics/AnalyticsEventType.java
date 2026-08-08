package com.wenxian.game.analytics;

/**
 * 统计层认可的有效产品行为。
 *
 * <p>枚举只描述稳定业务事实，不包含产品版本名称；登录不属于有效游戏活跃。</p>
 */
public enum AnalyticsEventType {
    USER_REGISTERED,
    SAVE_CREATED,
    TURN_SUCCEEDED,
    CORRECTION_APPLIED,
    ENTITY_REMOVED,
    SAVE_ARCHIVED
}

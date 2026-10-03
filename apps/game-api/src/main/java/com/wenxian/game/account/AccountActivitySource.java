package com.wenxian.game.account;

/** 玩家主动成功操作会延长保留期；LEGACY_BACKFILL 仅供受控存量重建，页面读取、会话保活和失败请求不在此列。 */
public enum AccountActivitySource {
    REGISTER,
    LOGIN,
    PASSWORD_CHANGED,
    SAVE_CREATED,
    TURN_COMMITTED,
    BYOK_CHANGED,
    BYOK_DELETED,
    DELETION_CANCELED,
    LEGACY_BACKFILL
}

package com.wenxian.game.account;

/** 不含玩家身份的账号生命周期长期事件类型。 */
enum AccountLifecycleEventType {
    NOTICE_PREPARED,
    NOTICE_SENT,
    NOTICE_RETRYABLE_FAILED,
    NOTICE_CANCELED,
    DELETE_DUE,
    DELETION_STARTED,
    DELETION_COMPLETED,
    DELETION_RETRYABLE_FAILED,
    DELETION_CANCELED
}

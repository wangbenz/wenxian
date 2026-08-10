package com.wenxian.game.story.application;

/**
 * 两阶段命卷修正的稳定业务失败。
 *
 * <p>该异常只表达失败阶段与原因，不携带模型密钥或数据库细节；HTTP 应用边界据此返回可操作提示，
 * 并始终声明失败没有消耗回合、没有改变命卷。</p>
 */
public final class StoryCorrectionException extends RuntimeException {
    private final Reason reason;

    public StoryCorrectionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public StoryCorrectionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        SAVE_NOT_FOUND,
        SAVE_NOT_ACTIVE,
        REVISION_CONFLICT,
        NO_STATE_CHANGE,
        PREVIEW_FAILED,
        PROPOSAL_INVALID,
        PROPOSAL_REJECTED
    }
}

package com.wenxian.game.story.agent;

/**
 * 模型供应商异常在中性 Story 边界内的统一表示。
 *
 * <p>业务层只依据失败类型决定玩家提示、是否允许重试和是否停用个人密钥，不读取供应商响应正文，
 * 也不把 LangChain4j 或具体模型的异常类型泄漏到 H5。该异常不代表回合已经提交。</p>
 */
public final class StoryModelFailureException extends RuntimeException {
    private final Kind kind;

    public StoryModelFailureException(Kind kind, String diagnostic, Throwable cause) {
        super(diagnostic == null ? "model request failed" : diagnostic, cause);
        if (kind == null) throw new IllegalArgumentException("模型失败类型不能为空");
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        AUTHENTICATION,
        REQUEST_REJECTED,
        RATE_LIMITED,
        CONTENT_FILTERED,
        TEMPORARY_UNAVAILABLE
    }
}

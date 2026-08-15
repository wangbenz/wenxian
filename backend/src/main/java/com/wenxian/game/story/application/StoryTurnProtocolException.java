package com.wenxian.game.story.application;

/**
 * 可信回合内部协议在完成一次自动修复后仍无法通过结构校验。
 *
 * <p>这是系统生成结果不符合约定，不是玩家行动与既有事实冲突。对外必须提示“本轮未提交，可重试”，
 * 不能要求玩家修改一个原本合法的行动；原始原因只进入诊断记录和问题编号。</p>
 */
public final class StoryTurnProtocolException extends RuntimeException {
    private final String stage;

    public StoryTurnProtocolException(String stage, String message, Throwable cause) {
        super(message == null || message.isBlank() ? "可信回合内部协议未通过" : message, cause);
        this.stage = stage == null || stage.isBlank() ? "UNKNOWN" : stage;
    }

    public String stage() {
        return stage;
    }
}

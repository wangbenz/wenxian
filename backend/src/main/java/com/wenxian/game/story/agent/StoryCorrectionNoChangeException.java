package com.wenxian.game.story.agent;

/**
 * 玩家的修正说明只澄清了叙事措辞，没有要改动的权威事实。
 *
 * <p>这不是模型或系统故障。应用层会将它转换为可理解的业务结果，
 * 明确告诉玩家当前客观状态未被错误改写，因此无需提交空补丁。</p>
 */
public final class StoryCorrectionNoChangeException extends RuntimeException {
    public StoryCorrectionNoChangeException(String message) {
        super(message);
    }
}

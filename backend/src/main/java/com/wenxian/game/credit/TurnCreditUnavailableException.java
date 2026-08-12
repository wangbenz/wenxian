package com.wenxian.game.credit;

/**
 * 账号没有可预占的平台回合时抛出的业务异常。
 *
 * <p>该异常不代表模型故障；未来接入玩家接口后应映射为明确的回合耗尽提示，
 * 不能显示为“天机紊乱”。</p>
 */
public class TurnCreditUnavailableException extends RuntimeException {
    private final String userId;

    public TurnCreditUnavailableException(String userId) {
        super("当前账号没有可用的平台回合");
        this.userId = userId;
    }

    public String userId() {
        return userId;
    }
}

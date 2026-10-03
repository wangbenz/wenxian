package com.wenxian.game.credit;

/**
 * 账号没有可预占的推演点时抛出的业务异常。
 *
 * <p>该异常不代表模型故障；玩家接口应将其映射为明确的点数耗尽提示，
 * 不能显示为“天机紊乱”。</p>
 */
public class TurnCreditUnavailableException extends RuntimeException {
    private final String userId;

    public TurnCreditUnavailableException(String userId) {
        super("当前账号没有可用的推演点");
        this.userId = userId;
    }

    public String userId() {
        return userId;
    }
}

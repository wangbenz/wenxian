package com.wenxian.game.credit;

/** 平台赠送回合不足的稳定业务异常；仅携带玩家可见提示，不包含模型实现信息。 */
public class PublicTurnLimitException extends RuntimeException {
    private final int turnLimit;
    private final String contactText;
    private final String supportGroup;

    public PublicTurnLimitException(String message, int turnLimit, String contactText, String supportGroup) {
        super(message);
        this.turnLimit = turnLimit;
        this.contactText = contactText;
        this.supportGroup = supportGroup;
    }

    public int turnLimit() {
        return turnLimit;
    }

    public String contactText() {
        return contactText;
    }

    public String supportGroup() {
        return supportGroup;
    }
}

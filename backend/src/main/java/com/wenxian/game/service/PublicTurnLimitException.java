package com.wenxian.game.service;

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

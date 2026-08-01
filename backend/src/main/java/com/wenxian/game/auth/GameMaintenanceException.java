package com.wenxian.game.auth;

public class GameMaintenanceException extends RuntimeException {
    private final String title;
    private final String resumeAt;

    public GameMaintenanceException(String title, String message, String resumeAt) {
        super(message);
        this.title = title;
        this.resumeAt = resumeAt;
    }

    public String title() { return title; }
    public String resumeAt() { return resumeAt; }
}

package com.wenxian.game.engine;

/**
 * Signals that a free-form action could not be converted into a trustworthy intent.
 * The caller must fail closed without advancing the game world.
 */
public class ActionInterpretationException extends RuntimeException {
    public ActionInterpretationException(String message, Throwable cause) {
        super(message, cause);
    }
}

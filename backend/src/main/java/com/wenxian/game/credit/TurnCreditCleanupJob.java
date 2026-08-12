package com.wenxian.game.credit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期归还进程异常遗留的回合预占，并关闭已经到期的赠送批次。 */
@Component
public class TurnCreditCleanupJob {
    private final TurnCreditService credits;
    private final boolean enabled;

    public TurnCreditCleanupJob(TurnCreditService credits,
                                @Value("${wenxian.turn-credit.enabled:false}") boolean enabled) {
        this.credits = credits;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${wenxian.turn-credit.cleanup-delay-ms:60000}")
    public void cleanup() {
        if (enabled) credits.cleanupExpired(200);
    }
}

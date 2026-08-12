package com.wenxian.game.credit;

import com.wenxian.game.credit.TurnCreditModels.Reservation;
import com.wenxian.game.credit.TurnCreditModels.ReserveCommand;
import com.wenxian.game.service.PlayerAiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 剧情推演访问平台回合的兼容门面。
 *
 * <p>自备密钥直接放行；未开启新账本时继续使用旧计数；开启后在模型调用前预占，
 * 正式回合事务中消费，失败则释放。这样 V0.15 可以独立灰度，不改变 V0.14 线上行为。</p>
 */
@Service
public class PlatformTurnAccessService {
    private final PlayerAiService playerAi;
    private final TurnCreditService credits;
    private final boolean enabled;

    public PlatformTurnAccessService(PlayerAiService playerAi,
                                     TurnCreditService credits,
                                     @Value("${wenxian.turn-credit.enabled:false}") boolean enabled) {
        this.playerAi = playerAi;
        this.credits = credits;
        this.enabled = enabled;
    }

    /** 调用模型前取得一次访问许可；新账本模式会立即预占一个平台回合。 */
    public Permit reserve(String userId, String saveId, String requestId, PlayerAiService.Route route) {
        if (route.custom()) return new Permit(Mode.PERSONAL, null, route);
        if (!enabled) {
            playerAi.assertPublicQuota(userId, route);
            return new Permit(Mode.LEGACY, null, route);
        }
        PlayerAiService.LegacyQuota legacy = playerAi.legacyQuota(userId);
        credits.ensureLegacyOpening(userId, legacy.turnLimit(), legacy.usedTurns());
        try {
            Reservation reservation = credits.reserve(new ReserveCommand(
                    userId, saveId, requestId, Duration.ofMinutes(10)));
            return new Permit(Mode.CREDIT, reservation.id(), route);
        } catch (TurnCreditUnavailableException unavailable) {
            playerAi.throwPublicTurnLimit();
            throw unavailable; // throwPublicTurnLimit 始终抛出，仅满足编译器控制流。
        }
    }

    /** 在剧情正式事务中确认消费；自备密钥不扣平台回合。 */
    public void consume(Permit permit, String userId, String turnId) {
        if (permit == null || permit.mode() == Mode.PERSONAL) return;
        if (permit.mode() == Mode.LEGACY) {
            playerAi.recordSuccessfulInvocation(userId, permit.route());
            return;
        }
        credits.consume(permit.reservationId(), turnId);
    }

    /** 模型、Reducer 或事务失败时归还预占；旧计数和自备密钥无需处理。 */
    public void release(Permit permit, String reason) {
        if (permit == null || permit.mode() != Mode.CREDIT) return;
        credits.release(permit.reservationId(), reason);
    }

    public boolean enabled() {
        return enabled;
    }

    public enum Mode {
        PERSONAL,
        LEGACY,
        CREDIT
    }

    public record Permit(Mode mode, String reservationId, PlayerAiService.Route route) {
    }
}

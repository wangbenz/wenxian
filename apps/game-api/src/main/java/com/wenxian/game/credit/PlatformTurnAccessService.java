package com.wenxian.game.credit;

import com.wenxian.game.credit.TurnCreditModels.Reservation;
import com.wenxian.game.credit.TurnCreditModels.ReserveCommand;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ServicePlanSelection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 剧情推演访问平台推演点的兼容门面。
 *
 * <p>自备密钥直接放行；未开启新账本时继续使用旧计数；开启后在模型调用前预占，
 * 正式回合事务中消费，失败则释放。额度规则与产品发布版本解耦。</p>
 */
@Service
public class PlatformTurnAccessService {
    private final PlatformTurnQuotaService quota;
    private final TurnCreditService credits;
    private final boolean enabled;

    public PlatformTurnAccessService(PlatformTurnQuotaService quota,
                                     TurnCreditService credits,
                                     @Value("${wenxian.turn-credit.enabled:false}") boolean enabled) {
        this.quota = quota;
        this.credits = credits;
        this.enabled = enabled;
    }

    /** 调用模型前取得一次访问许可；新账本模式会按冻结档位价格预占推演点。 */
    public Permit reserve(String userId, String saveId, String requestId, CredentialSource credentialSource) {
        return reserve(userId, saveId, requestId, credentialSource, null);
    }

    /** 调用模型前按已冻结方案价格预占；发布新方案不能改变同一 requestId 的金额。 */
    public Permit reserve(String userId, String saveId, String requestId, CredentialSource credentialSource,
                          ServicePlanSelection servicePlan) {
        if (credentialSource == null) throw new IllegalArgumentException("推演点判断缺少密钥来源");
        if (credentialSource == CredentialSource.USER) return new Permit(Mode.PERSONAL, null);
        if (!enabled) {
            quota.assertPlatformQuota(userId);
            return new Permit(Mode.LEGACY, null);
        }
        PlatformTurnQuotaService.LegacyQuota legacy = quota.legacyQuota(userId);
        credits.ensureLegacyOpening(userId, legacy.turnLimit(), legacy.usedTurns());
        try {
            Reservation reservation = credits.reserve(new ReserveCommand(
                    userId, saveId, requestId,
                    servicePlan == null ? 1 : servicePlan.pointCost(),
                    servicePlan == null ? null : servicePlan.planVersionId(),
                    servicePlan == null ? null : servicePlan.tierCode(),
                    Duration.ofMinutes(10)));
            return new Permit(Mode.CREDIT, reservation.id());
        } catch (TurnCreditUnavailableException unavailable) {
            quota.throwPublicTurnLimit();
            throw unavailable; // throwPublicTurnLimit 始终抛出，仅满足编译器控制流。
        }
    }

    /** 在剧情正式事务中确认消费；自备密钥不扣平台推演点。 */
    public void consume(Permit permit, String userId, String turnId) {
        if (permit == null || permit.mode() == Mode.PERSONAL) return;
        if (permit.mode() == Mode.LEGACY) {
            quota.recordSuccessfulPlatformInvocation(userId);
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

    public record Permit(Mode mode, String reservationId) {
    }
}

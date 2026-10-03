package com.wenxian.game.credit;

import java.time.Duration;
import java.time.Instant;

/**
 * 平台推演点账户的领域模型集合。
 *
 * <p>回合归属于玩家账号而不是单份命卷；模型只表达发放、预占和消费事实，
 * 不包含活动、签到、邀请或订单的具体业务规则。</p>
 */
public final class TurnCreditModels {
    private TurnCreditModels() {
    }

    public enum CreditClass {
        SPONSORED,
        PURCHASED
    }

    public enum GrantStatus {
        ACTIVE,
        EXHAUSTED,
        EXPIRED,
        REVOKED
    }

    public enum ReservationStatus {
        RESERVED,
        CONSUMED,
        RELEASED,
        EXPIRED
    }

    public enum LedgerEventType {
        GRANT,
        RESERVE,
        RELEASE,
        CONSUME,
        EXPIRE,
        REVOKE
    }

    /** 一次中性回合发放指令；外部业务必须提供稳定幂等键。 */
    public record GrantCommand(
            String userId,
            CreditClass creditClass,
            String grantType,
            String campaignCode,
            String sourceReference,
            String idempotencyKey,
            int count,
            Instant expiresAt,
            String reason,
            String createdBy) {
    }

    /** 已落库的回合批次；各数量之和必须始终等于 grantedCount。 */
    public record Grant(
            String id,
            String userId,
            CreditClass creditClass,
            String grantType,
            String campaignCode,
            String sourceReference,
            String idempotencyKey,
            int grantedCount,
            int availableCount,
            int reservedCount,
            int consumedCount,
            int expiredCount,
            int revokedCount,
            GrantStatus status,
            Instant expiresAt,
            String reason,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * 调用大模型前的临时预占指令；同一 requestId 的网络重试只能对应一份预占。
     *
     * <p>amount、方案版本和档位在请求开始时冻结，之后的模型重试或回退只能复用该预占，
     * 不能重新按当前配置计价。旧调用使用四参数构造器时保持一次预占 1 点。</p>
     */
    public record ReserveCommand(
            String userId,
            String saveId,
            String requestId,
            int amount,
            String servicePlanVersionId,
            String tierCode,
            Duration lifetime) {
        public ReserveCommand(String userId, String saveId, String requestId, Duration lifetime) {
            this(userId, saveId, requestId, 1, null, null, lifetime);
        }
    }

    /** 一份跨进程预占；grantId 是兼容期首批次指针，实际分摊以 allocation 表为准。 */
    public record Reservation(
            String id,
            String userId,
            String saveId,
            String requestId,
            String grantId,
            int amount,
            String servicePlanVersionId,
            String tierCode,
            ReservationStatus status,
            Instant expiresAt,
            String turnId,
            Instant createdAt,
            Instant updatedAt) {
        public Reservation(String id, String userId, String saveId, String requestId, String grantId,
                           int amount, ReservationStatus status, Instant expiresAt, String turnId,
                           Instant createdAt, Instant updatedAt) {
            this(id, userId, saveId, requestId, grantId, amount, null, null, status,
                    expiresAt, turnId, createdAt, updatedAt);
        }
    }

    /** 一份预占在某个赠送批次上的不可变分摊；各分摊之和必须等于预占总点数。 */
    public record ReservationAllocation(
            String reservationId,
            String grantId,
            int amount) {
    }

    /** 账号级平台推演点余额；四份命卷共享同一个结果。 */
    public record Balance(
            int granted,
            int available,
            int reserved,
            int consumed,
            int expired,
            int revoked,
            Instant nearestExpiry) {
    }
}

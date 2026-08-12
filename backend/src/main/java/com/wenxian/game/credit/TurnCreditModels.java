package com.wenxian.game.credit;

import java.time.Duration;
import java.time.Instant;

/**
 * 平台回合账户的领域模型集合。
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

    /** 调用大模型前的临时预占指令；同一 requestId 的网络重试只能对应一份预占。 */
    public record ReserveCommand(
            String userId,
            String saveId,
            String requestId,
            Duration lifetime) {
    }

    /** 一份跨进程预占；只有 RESERVED 状态允许消费或释放。 */
    public record Reservation(
            String id,
            String userId,
            String saveId,
            String requestId,
            String grantId,
            int amount,
            ReservationStatus status,
            Instant expiresAt,
            String turnId,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 账号级平台回合余额；四份命卷共享同一个结果。 */
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

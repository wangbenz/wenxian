package com.wenxian.game.credit;

import com.wenxian.game.credit.TurnCreditModels.Balance;
import com.wenxian.game.credit.TurnCreditModels.CreditClass;
import com.wenxian.game.credit.TurnCreditModels.Grant;
import com.wenxian.game.credit.TurnCreditModels.GrantCommand;
import com.wenxian.game.credit.TurnCreditModels.GrantStatus;
import com.wenxian.game.credit.TurnCreditModels.LedgerEventType;
import com.wenxian.game.credit.TurnCreditModels.Reservation;
import com.wenxian.game.credit.TurnCreditModels.ReservationAllocation;
import com.wenxian.game.credit.TurnCreditModels.ReservationStatus;
import com.wenxian.game.credit.TurnCreditModels.ReserveCommand;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 账号平台推演点账户的唯一业务入口。
 *
 * <p>发放方只提交中性奖励指令；剧情链路在调用模型前冻结方案、档位和点数并预占，
 * 正式回合事务中消费，失败时释放。一次预占可以跨多个赠送批次，但一次游戏行动只产生
 * 一份预占，模型重试和回退不得重复扣点。</p>
 */
@Service
public class TurnCreditService {
    private static final Duration DEFAULT_RESERVATION_LIFETIME = Duration.ofMinutes(10);
    private static final Set<String> SERVICE_TIERS = Set.of("LIGHT", "BALANCED", "DEEP");

    private final TurnCreditRepository repository;
    private final TransactionTemplate transactions;

    public TurnCreditService(TurnCreditRepository repository, TransactionTemplate transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    /** 发放一批回合；相同幂等键和相同业务内容重复调用时返回原批次。 */
    public Grant grant(GrantCommand command) {
        return grant(command, Instant.now());
    }

    /**
     * 首次启用回合账户时，把旧的“账号总额度/已用额度”固化为一笔期初批次。
     *
     * <p>同一账号只会建立一次期初批次；后续调整全局默认额度不会追改已经建立的账号余额，
     * 避免运营配置变化凭空增加或扣除玩家回合。</p>
     */
    public Grant ensureLegacyOpening(String userId, int grantedCount, int consumedCount) {
        return ensureLegacyOpening(userId, grantedCount, consumedCount, Instant.now());
    }

    Grant ensureLegacyOpening(String userId, int grantedCount, int consumedCount, Instant now) {
        String normalizedUserId = required(userId, "userId", 64);
        if (grantedCount <= 0 || grantedCount > 100_000) {
            throw new IllegalArgumentException("期初推演点总数必须在1到100000之间");
        }
        int normalizedConsumed = Math.max(0, Math.min(grantedCount, consumedCount));
        String idempotencyKey = "LEGACY_OPENING:" + normalizedUserId;
        Grant result;
        try {
            result = transactions.execute(status -> {
                var existing = repository.findGrantByIdempotencyKey(idempotencyKey);
                if (existing.isPresent()) return existing.get();
                int available = grantedCount - normalizedConsumed;
                GrantStatus grantStatus = available == 0 ? GrantStatus.EXHAUSTED : GrantStatus.ACTIVE;
                Grant grant = new Grant(id("credit-grant"), normalizedUserId, CreditClass.SPONSORED,
                        "LEGACY_OPENING", "V015_CREDIT_OPENING", "legacy-account-quota",
                        idempotencyKey, grantedCount, available, 0, normalizedConsumed, 0, 0,
                        grantStatus, null, "旧账号赠送额度按推演点建立期初账", "SYSTEM", now, now);
                repository.insertGrant(grant);
                int balanceAfter = repository.balance(normalizedUserId, now).available();
                repository.insertLedger(id("credit-ledger"), normalizedUserId, grant.id(), null,
                        LedgerEventType.GRANT, "GRANT:" + idempotencyKey, available, 0,
                        normalizedConsumed, balanceAfter, "legacy-account-quota", "SYSTEM",
                        "旧账号赠送额度按推演点建立期初账", now);
                return grant;
            });
        } catch (DuplicateKeyException race) {
            result = repository.findGrantByIdempotencyKey(idempotencyKey).orElseThrow();
        }
        if (result == null) throw new IllegalStateException("期初推演点迁移事务没有返回结果");
        return result;
    }

    Grant grant(GrantCommand command, Instant now) {
        GrantCommand normalized = normalize(command, now);
        try {
            Grant result = transactions.execute(status -> grantInTransaction(normalized, now));
            if (result == null) throw new IllegalStateException("回合发放事务没有返回结果");
            return result;
        } catch (DuplicateKeyException race) {
            return verifySameGrant(repository.findGrantByIdempotencyKey(normalized.idempotencyKey()).orElseThrow(), normalized);
        }
    }

    /** 返回账号级余额；命卷切换不会改变该余额。 */
    public Balance balance(String userId) {
        return balance(userId, Instant.now());
    }

    Balance balance(String userId, Instant now) {
        return repository.balance(required(userId, "userId", 64), now);
    }

    /** 在模型调用前预占推演点；相同账号、命卷和 requestId 的同价重试返回原预占。 */
    public Reservation reserve(ReserveCommand command) {
        return reserve(command, Instant.now());
    }

    Reservation reserve(ReserveCommand command, Instant now) {
        ReserveCommand normalized = normalize(command);
        try {
            Reservation result = transactions.execute(status -> reserveInTransaction(normalized, now));
            if (result == null) throw new IllegalStateException("回合预占事务没有返回结果");
            return result;
        } catch (DuplicateKeyException race) {
            return verifySameReservation(repository.findReservation(normalized.userId(), normalized.saveId(),
                    normalized.requestId()).orElseThrow(), normalized);
        }
    }

    /**
     * 将预占转换为正式消费。未来应在正式剧情回合的同一外层事务中调用；
     * 相同 turnId 重试保持幂等，不会重复扣减。
     */
    public Reservation consume(String reservationId, String turnId) {
        return consume(reservationId, turnId, Instant.now());
    }

    Reservation consume(String reservationId, String turnId, Instant now) {
        String normalizedReservationId = required(reservationId, "reservationId", 80);
        String normalizedTurnId = required(turnId, "turnId", 80);
        Reservation result = transactions.execute(status -> {
            Reservation reservation = repository.findReservationByIdForUpdate(normalizedReservationId)
                    .orElseThrow(() -> new IllegalArgumentException("推演点预占不存在"));
            if (reservation.status() == ReservationStatus.CONSUMED) {
                if (!Objects.equals(reservation.turnId(), normalizedTurnId)) {
                    throw new IllegalStateException("同一推演点预占不能关联两个正式回合");
                }
                return reservation;
            }
            if (reservation.status() != ReservationStatus.RESERVED) {
                throw new IllegalStateException("当前推演点预占已经失效，不能消费");
            }
            for (ReservationAllocation allocation : allocationsFor(reservation)) {
                repository.findGrantForUpdate(allocation.grantId())
                        .orElseThrow(() -> new IllegalStateException("推演点赠送批次不存在"));
                if (!repository.consumeGrant(allocation.grantId(), allocation.amount(), now)) {
                    throw new IllegalStateException("推演点消费发生并发冲突");
                }
                int balanceAfter = repository.balance(reservation.userId(), now).available();
                repository.insertLedger(id("credit-ledger"), reservation.userId(), allocation.grantId(),
                        reservation.id(), LedgerEventType.CONSUME,
                        "CONSUME:" + reservation.id() + ":" + allocation.grantId(),
                        0, -allocation.amount(), allocation.amount(), balanceAfter, normalizedTurnId,
                        "SYSTEM", "正式剧情回合提交后消费平台推演点", now);
            }
            if (!repository.markReservationConsumed(reservation.id(), normalizedTurnId, now)) {
                throw new IllegalStateException("推演点消费发生并发冲突");
            }
            return repository.findReservationByIdForUpdate(reservation.id()).orElseThrow();
        });
        if (result == null) throw new IllegalStateException("回合消费事务没有返回结果");
        return result;
    }

    /** 模型或业务失败时释放预占；已经释放的请求重复调用不会增加余额。 */
    public Reservation release(String reservationId, String reason) {
        return release(reservationId, reason, Instant.now());
    }

    Reservation release(String reservationId, String reason, Instant now) {
        String normalizedId = required(reservationId, "reservationId", 80);
        String normalizedReason = optional(reason, 240, "剧情回合未提交，释放推演点");
        Reservation result = transactions.execute(status -> releaseInTransaction(normalizedId, normalizedReason, now));
        if (result == null) throw new IllegalStateException("回合释放事务没有返回结果");
        return result;
    }

    /** 撤销尚未使用的发放批次；存在处理中预占时拒绝撤销，避免破坏守恒。 */
    public Grant revoke(String grantId, String reason, String operatorId) {
        return revoke(grantId, reason, operatorId, Instant.now());
    }

    Grant revoke(String grantId, String reason, String operatorId, Instant now) {
        String normalizedId = required(grantId, "grantId", 80);
        String normalizedReason = optional(reason, 240, "运营撤销未使用推演点");
        String normalizedOperator = required(operatorId, "operatorId", 64);
        Grant result = transactions.execute(status -> {
            Grant grant = repository.findGrantForUpdate(normalizedId)
                    .orElseThrow(() -> new IllegalArgumentException("推演点批次不存在"));
            if (grant.status() == GrantStatus.REVOKED) return grant;
            if (grant.reservedCount() > 0) {
                throw new IllegalStateException("批次仍有生成中的预占，暂时不能撤销");
            }
            int revoked = grant.availableCount();
            if (revoked == 0 || repository.revokeAvailableGrant(grant.id(), now) != 1) {
                throw new IllegalStateException("当前推演点批次没有可撤销余额");
            }
            int balanceAfter = repository.balance(grant.userId(), now).available();
            repository.insertLedger(id("credit-ledger"), grant.userId(), grant.id(), null,
                    LedgerEventType.REVOKE, "REVOKE:" + grant.id(), -revoked, 0, 0,
                    balanceAfter, grant.sourceReference(), normalizedOperator, normalizedReason, now);
            return repository.findGrantForUpdate(grant.id()).orElseThrow();
        });
        if (result == null) throw new IllegalStateException("回合撤销事务没有返回结果");
        return result;
    }

    /** 清理异常中断的预占和到期批次，返回本次处理记录数。 */
    public int cleanupExpired(int limit) {
        return cleanupExpired(limit, Instant.now());
    }

    int cleanupExpired(int limit, Instant now) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        int changed = 0;
        for (String reservationId : repository.findExpiredReservationIds(now, safeLimit)) {
            release(reservationId, "推演点预占超时自动释放", now);
            changed++;
        }
        int remaining = Math.max(0, safeLimit - changed);
        for (String grantId : repository.findDueGrantIds(now, remaining)) {
            if (expireGrant(grantId, now)) changed++;
        }
        return changed;
    }

    private Grant grantInTransaction(GrantCommand command, Instant now) {
        var existing = repository.findGrantByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) return verifySameGrant(existing.get(), command);
        Grant grant = new Grant(id("credit-grant"), command.userId(), command.creditClass(),
                command.grantType(), command.campaignCode(), command.sourceReference(), command.idempotencyKey(),
                command.count(), command.count(), 0, 0, 0, 0, GrantStatus.ACTIVE,
                command.expiresAt(), command.reason(), command.createdBy(), now, now);
        repository.insertGrant(grant);
        int balanceAfter = repository.balance(command.userId(), now).available();
        repository.insertLedger(id("credit-ledger"), command.userId(), grant.id(), null,
                LedgerEventType.GRANT, "GRANT:" + command.idempotencyKey(), command.count(), 0, 0,
                balanceAfter, command.sourceReference(), command.createdBy(), command.reason(), now);
        return grant;
    }

    private Reservation reserveInTransaction(ReserveCommand command, Instant now) {
        var existing = repository.findReservationForUpdate(command.userId(), command.saveId(), command.requestId());
        if (existing.isPresent()) {
            Reservation reservation = existing.get();
            if (reservation.status() == ReservationStatus.RESERVED
                    || reservation.status() == ReservationStatus.CONSUMED) {
                return verifySameReservation(reservation, command);
            }
            throw new IllegalStateException("该行动请求的推演点预占已经释放，不能复用 requestId");
        }

        List<Grant> grants = new ArrayList<>(repository.findSpendableGrantsForUpdate(command.userId(), now));
        grants.sort(Comparator
                .comparing(Grant::expiresAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(grant -> grant.creditClass() == CreditClass.SPONSORED ? 0 : 1)
                .thenComparing(Grant::createdAt)
                .thenComparing(Grant::id));
        List<ReservationAllocation> allocations = new ArrayList<>();
        int remaining = command.amount();
        String reservationId = id("credit-reservation");
        for (Grant grant : grants) {
            if (remaining == 0) break;
            int allocated = Math.min(remaining, grant.availableCount());
            allocations.add(new ReservationAllocation(reservationId, grant.id(), allocated));
            remaining -= allocated;
        }
        if (remaining > 0) {
            throw new TurnCreditUnavailableException(command.userId());
        }

        Reservation reservation = new Reservation(reservationId, command.userId(), command.saveId(),
                command.requestId(), allocations.getFirst().grantId(), command.amount(),
                command.servicePlanVersionId(), command.tierCode(), ReservationStatus.RESERVED,
                now.plus(command.lifetime()), null, now, now);
        repository.insertReservation(reservation);
        for (ReservationAllocation allocation : allocations) {
            if (!repository.reserveGrant(allocation.grantId(), allocation.amount(), now)) {
                throw new IllegalStateException("推演点预占发生并发冲突");
            }
            repository.insertReservationAllocation(allocation);
            int balanceAfter = repository.balance(command.userId(), now).available();
            repository.insertLedger(id("credit-ledger"), command.userId(), allocation.grantId(), reservation.id(),
                    LedgerEventType.RESERVE, "RESERVE:" + reservation.id() + ":" + allocation.grantId(),
                    -allocation.amount(), allocation.amount(), 0, balanceAfter, command.requestId(),
                    "SYSTEM", "剧情生成前预占平台推演点", now);
        }
        return reservation;
    }

    private Reservation releaseInTransaction(String reservationId, String reason, Instant now) {
        Reservation reservation = repository.findReservationByIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("推演点预占不存在"));
        if (reservation.status() == ReservationStatus.RELEASED
                || reservation.status() == ReservationStatus.EXPIRED) return reservation;
        if (reservation.status() == ReservationStatus.CONSUMED) {
            throw new IllegalStateException("已经随正式回合消费的推演点不能释放");
        }
        boolean anyExpired = false;
        for (ReservationAllocation allocation : allocationsFor(reservation)) {
            Grant grant = repository.findGrantForUpdate(allocation.grantId())
                    .orElseThrow(() -> new IllegalStateException("推演点赠送批次不存在"));
            boolean expired = grant.status() == GrantStatus.EXPIRED
                    || (grant.expiresAt() != null && !grant.expiresAt().isAfter(now));
            anyExpired |= expired;
            if (!repository.releaseGrant(grant.id(), allocation.amount(), expired, now)) {
                throw new IllegalStateException("推演点释放发生并发冲突");
            }
            int balanceAfter = repository.balance(reservation.userId(), now).available();
            repository.insertLedger(id("credit-ledger"), reservation.userId(), grant.id(), reservation.id(),
                    expired ? LedgerEventType.EXPIRE : LedgerEventType.RELEASE,
                    (expired ? "EXPIRE_RESERVATION:" : "RELEASE:") + reservation.id() + ":" + grant.id(),
                    expired ? 0 : allocation.amount(), -allocation.amount(), 0, balanceAfter,
                    reservation.requestId(), "SYSTEM", reason, now);
        }
        if (!repository.markReservationReleased(reservation.id(),
                anyExpired ? ReservationStatus.EXPIRED : ReservationStatus.RELEASED, now)) {
            throw new IllegalStateException("推演点释放发生并发冲突");
        }
        return repository.findReservationByIdForUpdate(reservation.id()).orElseThrow();
    }

    private boolean expireGrant(String grantId, Instant now) {
        Boolean changed = transactions.execute(status -> {
            Grant grant = repository.findGrantForUpdate(grantId).orElse(null);
            if (grant == null || grant.status() != GrantStatus.ACTIVE || grant.expiresAt() == null
                    || grant.expiresAt().isAfter(now)) return false;
            int expiring = grant.availableCount();
            if (repository.expireAvailableGrant(grant.id(), now) != 1) return false;
            if (expiring > 0) {
                int balanceAfter = repository.balance(grant.userId(), now).available();
                repository.insertLedger(id("credit-ledger"), grant.userId(), grant.id(), null,
                        LedgerEventType.EXPIRE, "EXPIRE_GRANT:" + grant.id(), -expiring, 0, 0,
                        balanceAfter, grant.sourceReference(), "SYSTEM", "推演点批次到期", now);
            }
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    private Grant verifySameGrant(Grant grant, GrantCommand command) {
        if (!grant.userId().equals(command.userId()) || grant.creditClass() != command.creditClass()
                || !grant.grantType().equals(command.grantType()) || grant.grantedCount() != command.count()
                || !Objects.equals(grant.campaignCode(), command.campaignCode())
                || !Objects.equals(grant.sourceReference(), command.sourceReference())
                || !Objects.equals(grant.expiresAt(), command.expiresAt())) {
            throw new IllegalStateException("同一回合发放幂等键对应了不同业务内容");
        }
        return grant;
    }

    private ReserveCommand normalize(ReserveCommand command) {
        if (command == null) throw new IllegalArgumentException("预占指令不能为空");
        Duration lifetime = command.lifetime() == null ? DEFAULT_RESERVATION_LIFETIME : command.lifetime();
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("推演点预占有效期必须在1秒到1小时之间");
        }
        if (command.amount() <= 0 || command.amount() > 100_000) {
            throw new IllegalArgumentException("单次推演点预占数量必须在1到100000之间");
        }
        String servicePlanVersionId = optional(command.servicePlanVersionId(), 64, null);
        String tierCode = optional(command.tierCode(), 16, null);
        if ((servicePlanVersionId == null) != (tierCode == null)) {
            throw new IllegalArgumentException("方案版本和推演档位必须同时提供");
        }
        if (tierCode != null) {
            tierCode = tierCode.toUpperCase();
            if (!SERVICE_TIERS.contains(tierCode)) {
                throw new IllegalArgumentException("推演档位只能是LIGHT、BALANCED或DEEP");
            }
        }
        return new ReserveCommand(required(command.userId(), "userId", 64),
                required(command.saveId(), "saveId", 80), required(command.requestId(), "requestId", 80),
                command.amount(), servicePlanVersionId, tierCode, lifetime);
    }

    private Reservation verifySameReservation(Reservation reservation, ReserveCommand command) {
        if (reservation.status() != ReservationStatus.RESERVED
                && reservation.status() != ReservationStatus.CONSUMED) {
            throw new IllegalStateException("该行动请求的推演点预占已经释放，不能复用 requestId");
        }
        if (reservation.amount() != command.amount()
                || !Objects.equals(reservation.servicePlanVersionId(), command.servicePlanVersionId())
                || !Objects.equals(reservation.tierCode(), command.tierCode())) {
            throw new IllegalStateException("同一行动请求不能使用不同的推演点价格或方案档位");
        }
        return reservation;
    }

    private List<ReservationAllocation> allocationsFor(Reservation reservation) {
        List<ReservationAllocation> allocations = repository.findReservationAllocations(reservation.id());
        if (allocations.isEmpty()) {
            allocations = List.of(new ReservationAllocation(
                    reservation.id(), reservation.grantId(), reservation.amount()));
        }
        int allocatedAmount = allocations.stream().mapToInt(ReservationAllocation::amount).sum();
        if (allocatedAmount != reservation.amount()) {
            throw new IllegalStateException("推演点预占分摊与冻结总额不一致");
        }
        return allocations;
    }

    private GrantCommand normalize(GrantCommand command, Instant now) {
        if (command == null) throw new IllegalArgumentException("发放指令不能为空");
        String userId = required(command.userId(), "userId", 64);
        Objects.requireNonNull(command.creditClass(), "creditClass不能为空");
        String grantType = required(command.grantType(), "grantType", 32);
        String idempotencyKey = required(command.idempotencyKey(), "idempotencyKey", 180);
        String reason = required(command.reason(), "reason", 240);
        String createdBy = required(command.createdBy(), "createdBy", 64);
        String campaignCode = optional(command.campaignCode(), 64, null);
        String sourceReference = optional(command.sourceReference(), 160, null);
        if (command.count() <= 0 || command.count() > 100_000) {
            throw new IllegalArgumentException("单批推演点数量必须在1到100000之间");
        }
        Instant expiresAt = command.expiresAt() == null ? null : command.expiresAt().truncatedTo(ChronoUnit.MICROS);
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("推演点到期时间必须晚于发放时间");
        }
        return new GrantCommand(userId, command.creditClass(), grantType, campaignCode, sourceReference,
                idempotencyKey, command.count(), expiresAt, reason, createdBy);
    }

    private String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
        String normalized = value.strip();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(field + "长度超出限制");
        return normalized;
    }

    private String optional(String value, int maxLength, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.strip();
        if (normalized.length() > maxLength) throw new IllegalArgumentException("文本长度超出限制");
        return normalized;
    }

    private String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}

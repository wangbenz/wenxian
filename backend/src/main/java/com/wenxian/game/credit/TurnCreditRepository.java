package com.wenxian.game.credit;

import com.wenxian.game.credit.TurnCreditModels.Balance;
import com.wenxian.game.credit.TurnCreditModels.CreditClass;
import com.wenxian.game.credit.TurnCreditModels.Grant;
import com.wenxian.game.credit.TurnCreditModels.GrantStatus;
import com.wenxian.game.credit.TurnCreditModels.LedgerEventType;
import com.wenxian.game.credit.TurnCreditModels.Reservation;
import com.wenxian.game.credit.TurnCreditModels.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 平台回合账户仓储。
 *
 * <p>本仓储只提供受条件更新、行锁和不可变流水写入；业务状态转换与事务由
 * {@link TurnCreditService} 统一控制。任何数量更新都必须保持批次守恒约束。</p>
 */
@Repository
public class TurnCreditRepository {
    private final JdbcTemplate jdbc;

    public TurnCreditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Grant> findGrantByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("select * from turn_credit_grant where idempotency_key=?",
                this::mapGrant, idempotencyKey).stream().findFirst();
    }

    public Optional<Grant> findGrantForUpdate(String grantId) {
        return jdbc.query("select * from turn_credit_grant where id=? for update",
                this::mapGrant, grantId).stream().findFirst();
    }

    public Optional<Grant> findSpendableGrantForUpdate(String userId, Instant now) {
        return jdbc.query("""
                select * from turn_credit_grant
                where user_id=? and status='ACTIVE' and available_count > 0
                  and (expires_at is null or expires_at > ?)
                order by case when expires_at is null then 1 else 0 end, expires_at asc,
                         case when credit_class='SPONSORED' then 0 else 1 end, created_at asc
                limit 1 for update
                """, this::mapGrant, userId, Timestamp.from(now)).stream().findFirst();
    }

    public void insertGrant(Grant grant) {
        jdbc.update("""
                insert into turn_credit_grant(
                    id,user_id,credit_class,grant_type,campaign_code,source_reference,idempotency_key,
                    granted_count,available_count,reserved_count,consumed_count,expired_count,revoked_count,
                    status,expires_at,reason,created_by,created_at,updated_at
                ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, grant.id(), grant.userId(), grant.creditClass().name(), grant.grantType(),
                grant.campaignCode(), grant.sourceReference(), grant.idempotencyKey(), grant.grantedCount(),
                grant.availableCount(), grant.reservedCount(), grant.consumedCount(), grant.expiredCount(),
                grant.revokedCount(), grant.status().name(), timestamp(grant.expiresAt()), grant.reason(),
                grant.createdBy(), Timestamp.from(grant.createdAt()), Timestamp.from(grant.updatedAt()));
    }

    public boolean reserveGrant(String grantId, int amount, Instant now) {
        return jdbc.update("""
                update turn_credit_grant
                set available_count=available_count-?, reserved_count=reserved_count+?, updated_at=?
                where id=? and status='ACTIVE' and available_count>=?
                  and (expires_at is null or expires_at > ?)
                """, amount, amount, Timestamp.from(now), grantId, amount, Timestamp.from(now)) == 1;
    }

    public boolean consumeGrant(String grantId, int amount, Instant now) {
        return jdbc.update("""
                update turn_credit_grant
                set reserved_count=reserved_count-?, consumed_count=consumed_count+?,
                    status=case when available_count=0 and reserved_count=? then 'EXHAUSTED' else status end,
                    updated_at=?
                where id=? and reserved_count>=? and status in ('ACTIVE','EXPIRED')
                """, amount, amount, amount, Timestamp.from(now), grantId, amount) == 1;
    }

    public boolean releaseGrant(String grantId, int amount, boolean grantExpired, Instant now) {
        if (grantExpired) {
            return jdbc.update("""
                    update turn_credit_grant
                    set reserved_count=reserved_count-?, expired_count=expired_count+?, status='EXPIRED', updated_at=?
                    where id=? and reserved_count>=? and status in ('ACTIVE','EXPIRED')
                    """, amount, amount, Timestamp.from(now), grantId, amount) == 1;
        }
        return jdbc.update("""
                update turn_credit_grant
                set reserved_count=reserved_count-?, available_count=available_count+?, status='ACTIVE', updated_at=?
                where id=? and reserved_count>=? and status='ACTIVE'
                """, amount, amount, Timestamp.from(now), grantId, amount) == 1;
    }

    public int expireAvailableGrant(String grantId, Instant now) {
        return jdbc.update("""
                update turn_credit_grant
                set expired_count=expired_count+available_count, available_count=0, status='EXPIRED', updated_at=?
                where id=? and status='ACTIVE' and expires_at is not null and expires_at<=?
                """, Timestamp.from(now), grantId, Timestamp.from(now));
    }

    public int revokeAvailableGrant(String grantId, Instant now) {
        return jdbc.update("""
                update turn_credit_grant
                set revoked_count=revoked_count+available_count, available_count=0, status='REVOKED', updated_at=?
                where id=? and status='ACTIVE' and reserved_count=0
                """, Timestamp.from(now), grantId);
    }

    public Optional<Reservation> findReservationForUpdate(String userId, String saveId, String requestId) {
        return jdbc.query("""
                select * from turn_credit_reservation
                where user_id=? and save_id=? and request_id=? for update
                """, this::mapReservation, userId, saveId, requestId).stream().findFirst();
    }

    public Optional<Reservation> findReservationByIdForUpdate(String reservationId) {
        return jdbc.query("select * from turn_credit_reservation where id=? for update",
                this::mapReservation, reservationId).stream().findFirst();
    }

    public void insertReservation(Reservation reservation) {
        jdbc.update("""
                insert into turn_credit_reservation(
                    id,user_id,save_id,request_id,grant_id,amount,status,expires_at,turn_id,created_at,updated_at
                ) values (?,?,?,?,?,?,?,?,?,?,?)
                """, reservation.id(), reservation.userId(), reservation.saveId(), reservation.requestId(),
                reservation.grantId(), reservation.amount(), reservation.status().name(),
                Timestamp.from(reservation.expiresAt()), reservation.turnId(),
                Timestamp.from(reservation.createdAt()), Timestamp.from(reservation.updatedAt()));
    }

    public boolean markReservationConsumed(String reservationId, String turnId, Instant now) {
        return jdbc.update("""
                update turn_credit_reservation set status='CONSUMED', turn_id=?, updated_at=?
                where id=? and status='RESERVED'
                """, turnId, Timestamp.from(now), reservationId) == 1;
    }

    public boolean markReservationReleased(String reservationId, ReservationStatus status, Instant now) {
        return jdbc.update("""
                update turn_credit_reservation set status=?, updated_at=?
                where id=? and status='RESERVED'
                """, status.name(), Timestamp.from(now), reservationId) == 1;
    }

    public List<String> findExpiredReservationIds(Instant now, int limit) {
        return jdbc.queryForList("""
                select id from turn_credit_reservation
                where status='RESERVED' and expires_at<=?
                order by expires_at asc limit ?
                """, String.class, Timestamp.from(now), limit);
    }

    public List<String> findDueGrantIds(Instant now, int limit) {
        return jdbc.queryForList("""
                select id from turn_credit_grant
                where status='ACTIVE' and expires_at is not null and expires_at<=?
                order by expires_at asc limit ?
                """, String.class, Timestamp.from(now), limit);
    }

    public Balance balance(String userId, Instant now) {
        return jdbc.query("""
                select coalesce(sum(granted_count),0) granted,
                       coalesce(sum(case when status='ACTIVE' and (expires_at is null or expires_at>?)
                                         then available_count else 0 end),0) available,
                       coalesce(sum(reserved_count),0) reserved,
                       coalesce(sum(consumed_count),0) consumed,
                       coalesce(sum(expired_count),0) expired,
                       coalesce(sum(revoked_count),0) revoked,
                       min(case when status='ACTIVE' and available_count>0 and expires_at>? then expires_at end) nearest_expiry
                from turn_credit_grant where user_id=?
                """, (row, index) -> new Balance(row.getInt("granted"), row.getInt("available"),
                row.getInt("reserved"), row.getInt("consumed"), row.getInt("expired"),
                row.getInt("revoked"), instant(row, "nearest_expiry")), Timestamp.from(now),
                Timestamp.from(now), userId).stream().findFirst().orElse(new Balance(0, 0, 0, 0, 0, 0, null));
    }

    public void insertLedger(String id, String userId, String grantId, String reservationId,
                             LedgerEventType type, String eventKey, int availableDelta,
                             int reservedDelta, int consumedDelta, int balanceAfter,
                             String businessReference, String operatorId, String remark, Instant now) {
        jdbc.update("""
                insert into turn_credit_ledger(
                    id,user_id,grant_id,reservation_id,event_type,event_key,available_delta,reserved_delta,
                    consumed_delta,balance_after,business_reference,operator_id,remark,created_at
                ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, userId, grantId, reservationId, type.name(), eventKey, availableDelta,
                reservedDelta, consumedDelta, balanceAfter, businessReference, operatorId, remark,
                Timestamp.from(now));
    }

    private Grant mapGrant(ResultSet row, int index) throws SQLException {
        return new Grant(row.getString("id"), row.getString("user_id"),
                CreditClass.valueOf(row.getString("credit_class")), row.getString("grant_type"),
                row.getString("campaign_code"), row.getString("source_reference"),
                row.getString("idempotency_key"), row.getInt("granted_count"),
                row.getInt("available_count"), row.getInt("reserved_count"), row.getInt("consumed_count"),
                row.getInt("expired_count"), row.getInt("revoked_count"),
                GrantStatus.valueOf(row.getString("status")), instant(row, "expires_at"),
                row.getString("reason"), row.getString("created_by"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }

    private Reservation mapReservation(ResultSet row, int index) throws SQLException {
        return new Reservation(row.getString("id"), row.getString("user_id"), row.getString("save_id"),
                row.getString("request_id"), row.getString("grant_id"), row.getInt("amount"),
                ReservationStatus.valueOf(row.getString("status")), row.getTimestamp("expires_at").toInstant(),
                row.getString("turn_id"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }

    private Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}

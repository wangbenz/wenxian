package com.wenxian.admin;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 管理台的平台回合账户适配器。
 *
 * <p>本服务只允许查询、发放赠送回合和撤销尚未使用的赠送批次，不提供直接修改余额的能力。
 * 每次数量变化同时写入不可变流水，并依赖批次守恒约束保护并发一致性。剧情回合的预占与消费仍只由
 * 游戏后端负责，管理台不得代替剧情事务消费回合。</p>
 */
@Service
public class TurnCreditAdminService {
    private static final Set<String> ALLOWED_GRANT_TYPES =
            Set.of("PLAYTEST", "FEEDBACK", "ACTIVITY", "ADMIN");

    private final JdbcTemplate jdbc;

    public TurnCreditAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按邮箱或用户ID分页查询账号余额，未进入新账本的旧账号会明确标记为待初始化。 */
    public Map<String, Object> accounts(String search, int page, int size) {
        String normalized = search == null ? "" : search.strip();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String pattern = "%" + normalized + "%";
        int legacyLimit = configInt("account_public_turn_limit", 20, 1, 1000);
        long total = number("""
                select count(*) from app_user u
                where (?='' or lower(u.email) like lower(?) or u.id like ?)
                """, normalized, pattern, pattern);
        List<Map<String, Object>> rows = jdbc.query("""
                select u.id user_id, u.email, u.status, u.last_login_at,
                       coalesce(sum(g.granted_count),0) granted_count,
                       coalesce(sum(case when g.status='ACTIVE' and (g.expires_at is null or g.expires_at>?)
                                         then g.available_count else 0 end),0) available_count,
                       coalesce(sum(g.reserved_count),0) reserved_count,
                       coalesce(sum(g.consumed_count),0) consumed_count,
                       coalesce(sum(g.expired_count),0) expired_count,
                       coalesce(sum(g.revoked_count),0) revoked_count,
                       min(case when g.status='ACTIVE' and g.available_count>0 and g.expires_at>?
                                then g.expires_at end) nearest_expiry,
                       coalesce(sum(case when g.grant_type='LEGACY_OPENING' then 1 else 0 end),0) opening_count,
                       coalesce((select sum(q.used_count) from user_daily_quota q where q.user_id=u.id),0) legacy_used_count
                from app_user u
                left join turn_credit_grant g on g.user_id=u.id
                where (?='' or lower(u.email) like lower(?) or u.id like ?)
                group by u.id,u.email,u.status,u.last_login_at
                order by u.last_login_at desc, u.id
                limit ? offset ?
                """, (row, index) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", row.getString("user_id"));
            item.put("email", row.getString("email"));
            item.put("status", row.getString("status"));
            item.put("lastLoginAt", instant(row.getTimestamp("last_login_at")));
            boolean initialized = row.getInt("opening_count") > 0;
            int legacyUsed = Math.min(legacyLimit, row.getInt("legacy_used_count"));
            item.put("granted", initialized ? row.getInt("granted_count") : legacyLimit);
            item.put("available", initialized ? row.getInt("available_count") : legacyLimit - legacyUsed);
            item.put("reserved", row.getInt("reserved_count"));
            item.put("consumed", initialized ? row.getInt("consumed_count") : legacyUsed);
            item.put("expired", row.getInt("expired_count"));
            item.put("revoked", row.getInt("revoked_count"));
            item.put("nearestExpiry", instant(row.getTimestamp("nearest_expiry")));
            item.put("ledgerInitialized", initialized);
            return item;
        }, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), normalized, pattern, pattern,
                safeSize, safePage * safeSize);
        return Map.of("total", total, "page", safePage, "size", safeSize, "rows", rows);
    }

    /** 返回账号余额、最近批次和最近流水，供运营核对而不是修改投影。 */
    public Map<String, Object> detail(String userId) {
        Map<String, Object> user = requireUser(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", user);
        result.put("balance", balance(userId));
        result.put("grants", jdbc.query("""
                select id,credit_class,grant_type,campaign_code,source_reference,granted_count,
                       available_count,reserved_count,consumed_count,expired_count,revoked_count,
                       status,expires_at,reason,created_by,created_at,updated_at
                from turn_credit_grant where user_id=? order by created_at desc limit 100
                """, (row, index) -> mapGrant(row), userId));
        result.put("ledger", jdbc.query("""
                select id,grant_id,reservation_id,event_type,available_delta,reserved_delta,consumed_delta,
                       balance_after,business_reference,operator_id,remark,created_at
                from turn_credit_ledger where user_id=? order by created_at desc limit 200
                """, (row, index) -> mapLedger(row), userId));
        return result;
    }

    /**
     * 向单个账号发放赠送回合。operationId 是调用方生成的稳定幂等号，同一操作重复提交不会重复赠送。
     */
    @Transactional
    public Map<String, Object> grant(String userId, GrantRequest request, String operatorId) {
        Map<String, Object> user = requireUser(userId);
        if (!"ACTIVE".equals(user.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该玩家账号当前不可用，不能赠送回合");
        }
        GrantRequest command = normalize(request);
        ensureLegacyOpening(userId);
        String idempotencyKey = "ADMIN:" + command.operationId();
        List<Map<String, Object>> existing = grantByIdempotency(idempotencyKey);
        if (!existing.isEmpty()) {
            verifySameGrant(existing.getFirst(), userId, command);
            return grantResult(userId, existing.getFirst().get("id").toString(), command, true);
        }
        Instant now = Instant.now();
        String grantId = id("credit-grant");
        try {
            jdbc.update("""
                    insert into turn_credit_grant(
                        id,user_id,credit_class,grant_type,campaign_code,source_reference,idempotency_key,
                        granted_count,available_count,reserved_count,consumed_count,expired_count,revoked_count,
                        status,expires_at,reason,created_by,created_at,updated_at
                    ) values (?,?,'SPONSORED',?,?,?,?,?,?,0,0,0,0,'ACTIVE',?,?,?,?,?)
                    """, grantId, userId, command.grantType(), blankToNull(command.campaignCode()),
                    blankToNull(command.sourceReference()), idempotencyKey, command.count(), command.count(),
                    timestamp(command.expiresAt()), command.reason(), operatorId,
                    Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException duplicate) {
            List<Map<String, Object>> raced = grantByIdempotency(idempotencyKey);
            if (raced.isEmpty()) throw duplicate;
            verifySameGrant(raced.getFirst(), userId, command);
            return grantResult(userId, raced.getFirst().get("id").toString(), command, true);
        }
        int balanceAfter = availableBalance(userId, now);
        jdbc.update("""
                insert into turn_credit_ledger(
                    id,user_id,grant_id,reservation_id,event_type,event_key,available_delta,reserved_delta,
                    consumed_delta,balance_after,business_reference,operator_id,remark,created_at
                ) values (?,?,?,null,'GRANT',?,?,0,0,?,?,?,?,?)
                """, id("credit-ledger"), userId, grantId, "GRANT:" + idempotencyKey,
                command.count(), balanceAfter, blankToNull(command.sourceReference()), operatorId,
                command.reason(), Timestamp.from(now));
        audit("GRANT_TURN_CREDIT", "管理员 " + operatorId + " 向账号 " + userId + " 赠送 "
                + command.count() + " 回（" + command.grantType() + "）");
        return grantResult(userId, grantId, command, false);
    }

    /**
     * 返回赠送后的账号详情和本次操作摘要。摘要由后端给出，避免前端从并发变化的批次列表猜测本次结果。
     */
    private Map<String, Object> grantResult(String userId, String grantId, GrantRequest command,
                                            boolean idempotent) {
        Map<String, Object> result = detail(userId);
        @SuppressWarnings("unchecked")
        Map<String, Object> balance = (Map<String, Object>) result.get("balance");
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("grantId", grantId);
        operation.put("count", command.count());
        operation.put("balanceAfter", balance.get("available"));
        operation.put("expiresAt", command.expiresAt() == null ? null : command.expiresAt().toString());
        operation.put("idempotent", idempotent);
        result.put("operation", operation);
        return result;
    }

    /** 撤销某批次当前尚未使用的赠送回合；已消费数量保留，存在预占时拒绝操作。 */
    @Transactional
    public Map<String, Object> revoke(String userId, String grantId, String reason, String operatorId) {
        requireUser(userId);
        String safeGrantId = required(grantId, "赠送批次", 80);
        String safeReason = required(reason, "撤销原因", 240);
        List<Map<String, Object>> grants = jdbc.query("""
                select id,user_id,credit_class,status,available_count,reserved_count
                from turn_credit_grant where id=? for update
                """, (row, index) -> Map.of(
                "id", row.getString("id"), "userId", row.getString("user_id"),
                "creditClass", row.getString("credit_class"), "status", row.getString("status"),
                "available", row.getInt("available_count"), "reserved", row.getInt("reserved_count")), safeGrantId);
        if (grants.isEmpty() || !userId.equals(grants.getFirst().get("userId"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "赠送批次不存在");
        }
        Map<String, Object> grant = grants.getFirst();
        if ("REVOKED".equals(grant.get("status"))) return detail(userId);
        if (!"SPONSORED".equals(grant.get("creditClass"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "非赠送回合不能由运营撤销");
        }
        int reserved = (int) grant.get("reserved");
        int available = (int) grant.get("available");
        if (reserved > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该批次仍有生成中的预占，请稍后再试");
        }
        if (available <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该批次已经没有可撤销回合");
        }
        Instant now = Instant.now();
        int updated = jdbc.update("""
                update turn_credit_grant
                set revoked_count=revoked_count+available_count,available_count=0,status='REVOKED',updated_at=?
                where id=? and user_id=? and status='ACTIVE' and reserved_count=0 and available_count>0
                """, Timestamp.from(now), safeGrantId, userId);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "批次状态已经变化，请刷新后重试");
        }
        int balanceAfter = availableBalance(userId, now);
        jdbc.update("""
                insert into turn_credit_ledger(
                    id,user_id,grant_id,reservation_id,event_type,event_key,available_delta,reserved_delta,
                    consumed_delta,balance_after,business_reference,operator_id,remark,created_at
                ) values (?,?,?,null,'REVOKE',?, ?,0,0,?, ?,?,?,?)
                """, id("credit-ledger"), userId, safeGrantId, "REVOKE:" + safeGrantId,
                -available, balanceAfter, safeGrantId, operatorId, safeReason, Timestamp.from(now));
        audit("REVOKE_TURN_CREDIT", "管理员 " + operatorId + " 撤销账号 " + userId + " 批次 "
                + safeGrantId + " 的 " + available + " 个未使用回合");
        return detail(userId);
    }

    private void ensureLegacyOpening(String userId) {
        String idempotencyKey = "LEGACY_OPENING:" + userId;
        if (!grantByIdempotency(idempotencyKey).isEmpty()) return;
        int limit = configInt("account_public_turn_limit", 20, 1, 1000);
        int used = (int) Math.min(limit, number(
                "select coalesce(sum(used_count),0) from user_daily_quota where user_id=?", userId));
        int available = limit - used;
        Instant now = Instant.now();
        String grantId = id("credit-grant");
        try {
            jdbc.update("""
                    insert into turn_credit_grant(
                        id,user_id,credit_class,grant_type,campaign_code,source_reference,idempotency_key,
                        granted_count,available_count,reserved_count,consumed_count,expired_count,revoked_count,
                        status,expires_at,reason,created_by,created_at,updated_at
                    ) values (?,?,'SPONSORED','LEGACY_OPENING','V015_CREDIT_OPENING','legacy-account-quota',
                              ?,?,?,0,?,0,0,?,null,'旧账号赠送回合期初迁移','SYSTEM',?,?)
                    """, grantId, userId, idempotencyKey, limit, available, used,
                    available == 0 ? "EXHAUSTED" : "ACTIVE", Timestamp.from(now), Timestamp.from(now));
            int balanceAfter = availableBalance(userId, now);
            jdbc.update("""
                    insert into turn_credit_ledger(
                        id,user_id,grant_id,reservation_id,event_type,event_key,available_delta,reserved_delta,
                        consumed_delta,balance_after,business_reference,operator_id,remark,created_at
                    ) values (?,?,?,null,'GRANT',?,?,0,?,?, 'legacy-account-quota','SYSTEM',
                              '旧账号赠送回合期初迁移',?)
                    """, id("credit-ledger"), userId, grantId, "GRANT:" + idempotencyKey,
                    available, used, balanceAfter, Timestamp.from(now));
        } catch (DuplicateKeyException race) {
            if (grantByIdempotency(idempotencyKey).isEmpty()) throw race;
        }
    }

    private Map<String, Object> balance(String userId) {
        Instant now = Instant.now();
        Map<String, Object> result = jdbc.queryForObject("""
                select coalesce(sum(granted_count),0) granted,
                       coalesce(sum(case when status='ACTIVE' and (expires_at is null or expires_at>?)
                                         then available_count else 0 end),0) available,
                       coalesce(sum(reserved_count),0) reserved,
                       coalesce(sum(consumed_count),0) consumed,
                       coalesce(sum(expired_count),0) expired,
                       coalesce(sum(revoked_count),0) revoked,
                       min(case when status='ACTIVE' and available_count>0 and expires_at>?
                                then expires_at end) nearest_expiry,
                       coalesce(sum(case when grant_type='LEGACY_OPENING' then 1 else 0 end),0) opening_count
                from turn_credit_grant where user_id=?
                """, (row, index) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("granted", row.getInt("granted"));
            item.put("available", row.getInt("available"));
            item.put("reserved", row.getInt("reserved"));
            item.put("consumed", row.getInt("consumed"));
            item.put("expired", row.getInt("expired"));
            item.put("revoked", row.getInt("revoked"));
            item.put("nearestExpiry", instant(row.getTimestamp("nearest_expiry")));
            item.put("ledgerInitialized", row.getInt("opening_count") > 0);
            return item;
        }, Timestamp.from(now), Timestamp.from(now), userId);
        if (Boolean.FALSE.equals(result.get("ledgerInitialized"))) {
            int limit = configInt("account_public_turn_limit", 20, 1, 1000);
            int used = (int) Math.min(limit, number(
                    "select coalesce(sum(used_count),0) from user_daily_quota where user_id=?", userId));
            result.put("granted", limit);
            result.put("available", limit - used);
            result.put("consumed", used);
        }
        return result;
    }

    private int availableBalance(String userId, Instant now) {
        return Math.toIntExact(number("""
                select coalesce(sum(case when status='ACTIVE' and (expires_at is null or expires_at>?)
                                         then available_count else 0 end),0)
                from turn_credit_grant where user_id=?
                """, Timestamp.from(now), userId));
    }

    private List<Map<String, Object>> grantByIdempotency(String key) {
        return jdbc.query("""
                select id,user_id,grant_type,campaign_code,source_reference,idempotency_key,
                       granted_count,expires_at,reason
                from turn_credit_grant where idempotency_key=?
                """, (row, index) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getString("id"));
            item.put("userId", row.getString("user_id"));
            item.put("grantType", row.getString("grant_type"));
            item.put("campaignCode", row.getString("campaign_code"));
            item.put("sourceReference", row.getString("source_reference"));
            item.put("idempotencyKey", row.getString("idempotency_key"));
            item.put("count", row.getInt("granted_count"));
            item.put("expiresAt", row.getTimestamp("expires_at") == null
                    ? null : row.getTimestamp("expires_at").toInstant());
            item.put("reason", row.getString("reason"));
            return item;
        }, key);
    }

    private void verifySameGrant(Map<String, Object> existing, String userId, GrantRequest command) {
        if (!userId.equals(existing.get("userId"))
                || !command.grantType().equals(existing.get("grantType"))
                || command.count() != (int) existing.get("count")
                || !java.util.Objects.equals(blankToNull(command.campaignCode()), existing.get("campaignCode"))
                || !java.util.Objects.equals(blankToNull(command.sourceReference()), existing.get("sourceReference"))
                || !java.util.Objects.equals(command.expiresAt(), existing.get("expiresAt"))
                || !command.reason().equals(existing.get("reason"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该操作编号已经用于另一笔赠送，请刷新后重试");
        }
    }

    private Map<String, Object> requireUser(String userId) {
        String normalized = required(userId, "用户", 64);
        List<Map<String, Object>> users = jdbc.query("""
                select id,email,status,registered_at,last_login_at from app_user where id=?
                """, (row, index) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", row.getString("id"));
            item.put("email", row.getString("email"));
            item.put("status", row.getString("status"));
            item.put("registeredAt", instant(row.getTimestamp("registered_at")));
            item.put("lastLoginAt", instant(row.getTimestamp("last_login_at")));
            return item;
        }, normalized);
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "玩家账号不存在");
        return users.getFirst();
    }

    private Map<String, Object> mapGrant(java.sql.ResultSet row) throws java.sql.SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        Timestamp expiryTimestamp = row.getTimestamp("expires_at");
        boolean projectedExpired = "ACTIVE".equals(row.getString("status"))
                && expiryTimestamp != null && !expiryTimestamp.toInstant().isAfter(Instant.now());
        int storedAvailable = row.getInt("available_count");
        item.put("id", row.getString("id"));
        item.put("creditClass", row.getString("credit_class"));
        item.put("grantType", row.getString("grant_type"));
        item.put("campaignCode", row.getString("campaign_code"));
        item.put("sourceReference", row.getString("source_reference"));
        item.put("granted", row.getInt("granted_count"));
        item.put("available", projectedExpired ? 0 : storedAvailable);
        item.put("reserved", row.getInt("reserved_count"));
        item.put("consumed", row.getInt("consumed_count"));
        item.put("expired", row.getInt("expired_count") + (projectedExpired ? storedAvailable : 0));
        item.put("revoked", row.getInt("revoked_count"));
        item.put("status", projectedExpired ? "EXPIRED" : row.getString("status"));
        item.put("expiresAt", instant(expiryTimestamp));
        item.put("reason", row.getString("reason"));
        item.put("createdBy", row.getString("created_by"));
        item.put("createdAt", instant(row.getTimestamp("created_at")));
        item.put("updatedAt", instant(row.getTimestamp("updated_at")));
        return item;
    }

    private Map<String, Object> mapLedger(java.sql.ResultSet row) throws java.sql.SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.getString("id"));
        item.put("grantId", row.getString("grant_id"));
        item.put("reservationId", row.getString("reservation_id"));
        item.put("eventType", row.getString("event_type"));
        item.put("availableDelta", row.getInt("available_delta"));
        item.put("reservedDelta", row.getInt("reserved_delta"));
        item.put("consumedDelta", row.getInt("consumed_delta"));
        item.put("balanceAfter", row.getInt("balance_after"));
        item.put("businessReference", row.getString("business_reference"));
        item.put("operatorId", row.getString("operator_id"));
        item.put("remark", row.getString("remark"));
        item.put("createdAt", instant(row.getTimestamp("created_at")));
        return item;
    }

    private GrantRequest normalize(GrantRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "赠送信息不能为空");
        String operationId = required(request.operationId(), "操作编号", 80);
        if (!operationId.matches("[A-Za-z0-9:_-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "操作编号格式不正确");
        }
        String type = required(request.grantType(), "赠送来源", 32).toUpperCase();
        if (!ALLOWED_GRANT_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持该赠送来源");
        }
        if (request.count() < 1 || request.count() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单次赠送回合需在1到1000之间");
        }
        String reason = required(request.reason(), "赠送原因", 240);
        String campaign = optional(request.campaignCode(), "活动编码", 64);
        String reference = optional(request.sourceReference(), "来源编号", 160);
        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "到期时间必须晚于当前时间");
        }
        return new GrantRequest(operationId, type, request.count(), campaign, reference,
                request.expiresAt(), reason);
    }

    private int configInt(String key, int fallback, int min, int max) {
        String value = jdbc.query("select config_value from gameplay_config where config_key=?",
                (row, index) -> row.getString(1), key).stream().findFirst().orElse(Integer.toString(fallback));
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long number(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private void audit(String operation, String detail) {
        jdbc.update("insert into admin_operation_log(id,operated_at,operation,detail) values (?,?,?,?)",
                id("admin-op"), Timestamp.from(Instant.now()), operation, detail);
    }

    private static String required(String value, String label, int maxLength) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空");
        if (normalized.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能超过" + maxLength + "个字符");
        }
        return normalized;
    }

    private static String optional(String value, String label, int maxLength) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能超过" + maxLength + "个字符");
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String instant(Timestamp value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 管理台单次回合赠送请求。 */
    public record GrantRequest(String operationId, String grantType, int count, String campaignCode,
                               String sourceReference, Instant expiresAt, String reason) {
    }
}

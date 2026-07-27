package com.wenxian.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private static final int SESSION_DAYS = 7;

    public AdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void ensureDefaultUser() {
        int count = count("SELECT count(*) FROM admin_user");
        if (count == 0) {
            String hash = sha256("wenxian@2025");
            jdbc.update("""
                    INSERT INTO admin_user(id, username, password_hash, status, created_at)
                    VALUES (?, ?, ?, 'ACTIVE', ?)
                    """, "admin-" + UUID.randomUUID(), "wenxian", hash, Timestamp.from(Instant.now()));
        }
    }

    // ---- auth ----

    public Map<String, Object> login(String username, String password) {
        String hash = sha256(password);
        var users = jdbc.query(
                "SELECT id FROM admin_user WHERE username = ? AND password_hash = ? AND status = 'ACTIVE'",
                (row, idx) -> row.getString("id"), username, hash);
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");

        String userId = users.getFirst();
        String token = randomToken();
        String tokenHash = sha256(token);
        Instant now = Instant.now();
        Instant expires = now.plus(Duration.ofDays(SESSION_DAYS));
        jdbc.update("""
                INSERT INTO admin_session(id, user_id, token_hash, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, "adminsess-" + UUID.randomUUID(), userId, tokenHash, Timestamp.from(expires), Timestamp.from(now));
        audit("LOGIN", "管理员 " + username + " 登录管理台");
        return Map.of("ok", true, "token", token);
    }

    @Transactional
    public void changePassword(String token, String oldPassword, String newPassword) {
        String tokenHash = sha256(token);
        var sessions = jdbc.query(
                "SELECT user_id FROM admin_session WHERE token_hash = ? AND expires_at > ?",
                (row, idx) -> row.getString("user_id"),
                tokenHash, Timestamp.from(Instant.now()));
        if (sessions.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"登录已过期，请重新登录");

        String userId = sessions.getFirst();
        String oldHash = sha256(oldPassword);
        int matched = count("SELECT count(*) FROM admin_user WHERE id = ? AND password_hash = ?", userId, oldHash);
        if (matched == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"旧密码不正确");

        String newHash = sha256(newPassword);
        jdbc.update("UPDATE admin_user SET password_hash = ? WHERE id = ?", newHash, userId);
        jdbc.update("DELETE FROM admin_session WHERE user_id = ?", userId);
        audit("CHANGE_PASSWORD", "管理员修改密码");
    }

    // ---- dashboard ----

    public Map<String, Object> dashboard() {
        int totalUsers = count("SELECT count(*) FROM app_user WHERE status = 'ACTIVE'");
        int onlineUsers = count("""
                SELECT count(DISTINCT user_id) FROM user_session
                WHERE revoked_at IS NULL AND expires_at > NOW()
                """);
        int totalSaves = count("SELECT count(*) FROM game_save");
        int totalTurns = count("SELECT count(*) FROM game_turn");
        int todayTurns = count("SELECT count(*) FROM game_turn WHERE created_at > CURDATE()");
        int totalTokens = sum("SELECT coalesce(sum(prompt_tokens + completion_tokens), 0) FROM game_turn");
        Double avgLatency = jdbc.queryForObject(
                "SELECT coalesce(avg(latency_ms), 0) FROM game_turn WHERE latency_ms > 0", Double.class);

        List<Map<String, Object>> recentTurns = jdbc.query("""
                SELECT gt.turn_number, gt.player_action,
                       gt.prompt_tokens + gt.completion_tokens AS tokens,
                       gt.latency_ms, gt.model, gt.created_at, gs.state_json
                FROM game_turn gt JOIN game_save gs ON gs.id = gt.save_id
                ORDER BY gt.created_at DESC LIMIT 8
                """, (row, idx) -> {
            String playerName = "";
            String realm = "";
            try {
                JsonNode state = mapper.readTree(row.getString("state_json"));
                JsonNode player = state.path("player");
                playerName = player.path("name").asText("");
                realm = player.path("realm").asText("");
            } catch (Exception ignored) { }
            return Map.<String, Object>of(
                    "playerName", playerName,
                    "realm", realm,
                    "turn", row.getInt("turn_number"),
                    "action", truncate(row.getString("player_action"), 80),
                    "tokens", row.getInt("tokens"),
                    "latency", row.getLong("latency_ms"),
                    "model", row.getString("model") != null ? row.getString("model") : "",
                    "at", row.getTimestamp("created_at").toInstant().toString()
            );
        });

        return Map.of(
                "totalUsers", totalUsers, "onlineUsers", onlineUsers,
                "totalSaves", totalSaves, "totalTurns", totalTurns,
                "todayTurns", todayTurns, "totalTokens", totalTokens,
                "avgLatency", Math.round(avgLatency != null ? avgLatency : 0),
                "recentTurns", recentTurns
        );
    }

    public List<Map<String, Object>> inviteCodes(String status) {
        String filter = status == null || status.isBlank() ? "%" : status.strip();
        return jdbc.query("""
                SELECT ic.id, ic.status, ic.max_uses, ic.used_count, ic.expires_at, ic.created_at,
                       aic.code_label
                FROM invite_code ic
                LEFT JOIN admin_invite_code aic ON aic.invite_code_id = ic.id
                WHERE ic.status LIKE ?
                ORDER BY ic.created_at DESC
                """, (row, idx) -> {
            Map<String, Object> code = new LinkedHashMap<>();
            code.put("id", row.getString("id"));
            String label = row.getString("code_label");
            code.put("codeDisplay", label != null ? label : codeIdLabel(row.getString("id")));
            code.put("status", row.getString("status"));
            code.put("maxUses", row.getInt("max_uses"));
            code.put("usedCount", row.getInt("used_count"));
            if (row.getTimestamp("expires_at") != null) {
                code.put("expiresAt", row.getTimestamp("expires_at").toInstant().toString());
            }
            code.put("createdAt", row.getTimestamp("created_at").toInstant().toString());
            return code;
        }, filter);
    }

    @Transactional
    public List<String> generateInviteCodes(int count, int maxUses) {
        if (count < 1 || count > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"数量需在 1-50 之间");
        if (maxUses < 1 || maxUses > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"使用次数需在 1-200 之间");
        Instant now = Instant.now();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code = "WX-" + randomCode(10);
            String hash = sha256(code);
            String inviteId = "invite-" + UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO invite_code(id, code_hash, status, max_uses, used_count, expires_at, created_at)
                    VALUES (?, ?, 'ACTIVE', ?, 0, NULL, ?)
                    """, inviteId, hash, maxUses, Timestamp.from(now));
            jdbc.update("""
                    INSERT INTO admin_invite_code(id, invite_code_id, code_label, created_at)
                    VALUES (?, ?, ?, ?)
                    """, "aic-" + UUID.randomUUID(), inviteId, code, Timestamp.from(now));
            codes.add(code);
        }
        audit("GENERATE_INVITES", "批量生成 " + count + " 个内测码（每码可用 " + maxUses + " 次）");
        return codes;
    }

    public List<Map<String, Object>> users(String search, int page, int size) {
        String like = search == null || search.isBlank() ? "%" : "%" + search.strip() + "%";
        int offset = Math.max(0, page) * Math.max(1, Math.min(size, 50));
        int limit = Math.max(1, Math.min(size, 50));

        int total = count("SELECT count(*) FROM app_user WHERE status = 'ACTIVE' AND email LIKE ?", like);

        List<Map<String, Object>> rows = jdbc.query("""
                SELECT u.id, u.email, u.registered_at, u.last_login_at,
                       gs.id AS save_id, gs.state_json, gs.updated_at AS save_updated,
                       (SELECT count(*) FROM game_turn gt WHERE gt.save_id = gs.id) AS turn_count,
                       (SELECT coalesce(sum(gt2.prompt_tokens + gt2.completion_tokens), 0)
                        FROM game_turn gt2 WHERE gt2.save_id = gs.id) AS total_tokens
                FROM app_user u
                LEFT JOIN game_save gs ON gs.user_id = u.id
                WHERE u.status = 'ACTIVE' AND u.email LIKE ?
                ORDER BY u.last_login_at DESC
                LIMIT ? OFFSET ?
                """, (row, idx) -> {
            String playerName = "", realm = "", race = "";
            try {
                JsonNode state = mapper.readTree(row.getString("state_json"));
                JsonNode player = state.path("player");
                playerName = player.path("name").asText("");
                realm = player.path("realm").asText("");
                race = player.path("race").asText("");
            } catch (Exception ignored) { }
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("userId", row.getString("id"));
            user.put("email", maskEmail(row.getString("email")));
            user.put("rawEmail", row.getString("email"));
            user.put("registeredAt", row.getTimestamp("registered_at").toInstant().toString());
            user.put("lastLoginAt", row.getTimestamp("last_login_at") != null
                    ? row.getTimestamp("last_login_at").toInstant().toString() : null);
            user.put("saveId", row.getString("save_id"));
            user.put("playerName", playerName);
            user.put("realm", realm);
            user.put("race", race);
            user.put("turnCount", row.getInt("turn_count"));
            user.put("totalTokens", row.getInt("total_tokens"));
            user.put("saveUpdated", row.getTimestamp("save_updated") != null
                    ? row.getTimestamp("save_updated").toInstant().toString() : null);
            return user;
        }, like, limit, offset);

        return List.of(
                Map.<String, Object>of("total", total, "page", page, "size", limit),
                Map.<String, Object>of("rows", rows)
        );
    }

    public Map<String, Object> userDetail(String userId) {
        var users = jdbc.query("""
                SELECT id, email, status, registered_at, last_login_at
                FROM app_user WHERE id = ?
                """, (row, idx) -> {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("userId", row.getString("id"));
            u.put("email", maskEmail(row.getString("email")));
            u.put("status", row.getString("status"));
            u.put("registeredAt", row.getTimestamp("registered_at").toInstant().toString());
            if (row.getTimestamp("last_login_at") != null) {
                u.put("lastLoginAt", row.getTimestamp("last_login_at").toInstant().toString());
            }
            return u;
        }, userId);
        if (users.isEmpty()) return Map.of("error", "用户不存在");

        var turns = jdbc.query("""
                SELECT turn_number, player_action, prompt_tokens, completion_tokens,
                       latency_ms, model, response_id, created_at
                FROM game_turn WHERE save_id = (SELECT id FROM game_save WHERE user_id = ? LIMIT 1)
                ORDER BY turn_number DESC LIMIT 50
                """, (row, idx) -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("turn", row.getInt("turn_number"));
            t.put("action", row.getString("player_action"));
            t.put("promptTokens", row.getInt("prompt_tokens"));
            t.put("completionTokens", row.getInt("completion_tokens"));
            t.put("latency", row.getLong("latency_ms"));
            t.put("model", row.getString("model"));
            if (row.getString("response_id") != null) {
                t.put("responseId", row.getString("response_id"));
            }
            t.put("at", row.getTimestamp("created_at").toInstant().toString());
            return t;
        }, userId);

        var executions = jdbc.query("""
                SELECT turn_number, attempt_number, status, detail, model,
                       response_id, latency_ms, created_at
                FROM agent_execution WHERE save_id = (SELECT id FROM game_save WHERE user_id = ? LIMIT 1)
                ORDER BY created_at DESC LIMIT 50
                """, (row, idx) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("turn", row.getInt("turn_number"));
            e.put("attempt", row.getInt("attempt_number"));
            e.put("status", row.getString("status"));
            e.put("detail", row.getString("detail"));
            e.put("model", row.getString("model"));
            if (row.getString("response_id") != null) {
                e.put("responseId", row.getString("response_id"));
            }
            e.put("latency", row.getLong("latency_ms"));
            e.put("at", row.getTimestamp("created_at").toInstant().toString());
            return e;
        }, userId);

        return Map.of("user", users.getFirst(), "turns", turns, "executions", executions);
    }

    // ---- helpers ----

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result != null ? result : 0;
    }

    private int sum(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result != null ? result : 0;
    }

    private void audit(String operation, String detail) {
        try {
            jdbc.update("""
                    INSERT INTO admin_operation_log(id, operated_at, operation, detail)
                    VALUES (?, ?, ?, ?)
                    """, "admin-op-" + UUID.randomUUID(), Timestamp.from(Instant.now()),
                    operation, detail);
        } catch (Exception ignored) { }
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String randomCode(int length) {
        byte[] bytes = new byte[(length + 3) / 4 * 3];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                .replaceAll("[0-9OIl]", "").substring(0, Math.min(length, 12));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String maskEmail(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(Math.max(0, at));
        return email.charAt(0) + "***" + email.substring(at - 1);
    }

    private static String codeIdLabel(String id) {
        // Format invite-xxx-xxx-xxx as readable code: take the last 3 segments
        String[] parts = id.split("-");
        if (parts.length >= 4) {
            return "WX-" + parts[parts.length - 3].substring(0, Math.min(4, parts[parts.length - 3].length()))
                    + "-" + parts[parts.length - 2].substring(0, Math.min(4, parts[parts.length - 2].length()));
        }
        return id.length() > 16 ? id.substring(0, 16) : id;
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}

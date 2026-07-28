package com.wenxian.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AdminService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private static final int SESSION_DAYS = 7;
    private static final long DASHBOARD_CACHE_MILLIS = 10_000;
    private static final char[] INVITE_CODE_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int INVITE_CODE_LENGTH = 10;
    private final String aiConfigMasterKey;
    private volatile Map<String, Object> dashboardCache;
    private volatile long dashboardCacheExpiresAt;

    @Autowired
    public AdminService(JdbcTemplate jdbc,
                        @Value("${wenxian.ai-config-master-key:wenxian-local-config-key-change-me}") String aiConfigMasterKey) {
        this.jdbc = jdbc;
        this.aiConfigMasterKey = aiConfigMasterKey;
    }

    AdminService(JdbcTemplate jdbc) {
        this(jdbc, "wenxian-local-config-key-change-me");
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
        long nowMillis = System.currentTimeMillis();
        Map<String, Object> cached = dashboardCache;
        if (cached != null && nowMillis < dashboardCacheExpiresAt) return cached;

        ZoneId reportingZone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(reportingZone);
        Timestamp todayStart = Timestamp.from(today.atStartOfDay(reportingZone).toInstant());
        int totalUsers = count("SELECT count(*) FROM app_user WHERE status = 'ACTIVE'");
        Integer todayActiveValue = jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE last_login_at >= ?", Integer.class, todayStart);
        int todayActive = todayActiveValue == null ? 0 : todayActiveValue;
        Integer onlinePlayers = jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE status = 'ACTIVE' AND last_login_at >= ?",
                Integer.class, Timestamp.from(Instant.now().minus(Duration.ofMinutes(15))));
        int totalSaves = count("SELECT count(*) FROM game_save");
        Map<String, Number> turnMetrics = jdbc.queryForObject("""
                SELECT count(*) total_turns,
                       coalesce(sum(case when created_at >= ? then 1 else 0 end), 0) today_turns,
                       coalesce(sum(prompt_tokens + completion_tokens), 0) total_tokens,
                       coalesce(sum(case when created_at >= ?
                           then prompt_tokens + completion_tokens else 0 end), 0) today_tokens,
                       coalesce(avg(case when latency_ms > 0 then latency_ms end), 0) avg_latency
                FROM (
                    SELECT created_at, prompt_tokens, completion_tokens, latency_ms FROM game_turn
                    UNION ALL
                    SELECT created_at, prompt_tokens, completion_tokens, latency_ms FROM game_life_turn_archive
                ) all_turns
                """, (row, index) -> Map.of(
                "totalTurns", row.getLong("total_turns"),
                "todayTurns", row.getLong("today_turns"),
                "totalTokens", row.getLong("total_tokens"),
                "todayTokens", row.getLong("today_tokens"),
                "avgLatency", row.getDouble("avg_latency")
        ), todayStart, todayStart);
        Integer failedTodayValue = jdbc.queryForObject(
                "SELECT count(*) FROM agent_execution WHERE status = 'FAILED' AND created_at >= ?",
                Integer.class, todayStart);
        int failedToday = failedTodayValue == null ? 0 : failedTodayValue;

        Timestamp tokenSince = Timestamp.from(Instant.now().minus(Duration.ofDays(14)));
        List<Map<String, Object>> rawDailyTokens = jdbc.query("""
                SELECT DATE(token_usage.created_at) usage_date,
                       coalesce(sum(token_usage.prompt_tokens), 0) prompt_tokens,
                       coalesce(sum(token_usage.completion_tokens), 0) completion_tokens,
                       count(*) turn_count,
                       count(distinct token_usage.user_id) player_count
                FROM (
                    SELECT gt.created_at, gt.prompt_tokens, gt.completion_tokens, gs.user_id
                    FROM game_turn gt JOIN game_save gs ON gs.id = gt.save_id
                    WHERE gt.created_at >= ?
                    UNION ALL
                    SELECT gta.created_at, gta.prompt_tokens, gta.completion_tokens, gla.user_id
                    FROM game_life_turn_archive gta
                    JOIN game_life_archive gla ON gla.id = gta.life_archive_id
                    WHERE gta.created_at >= ?
                ) token_usage
                GROUP BY DATE(token_usage.created_at)
                ORDER BY usage_date
                """, (row, index) -> Map.<String, Object>of(
                "date", row.getDate("usage_date").toLocalDate().toString(),
                "promptTokens", row.getLong("prompt_tokens"),
                "completionTokens", row.getLong("completion_tokens"),
                "turns", row.getLong("turn_count"),
                "players", row.getLong("player_count")
        ), tokenSince, tokenSince);
        Map<String, Map<String, Object>> dailyByDate = new LinkedHashMap<>();
        rawDailyTokens.forEach(day -> dailyByDate.put(String.valueOf(day.get("date")), day));
        List<Map<String, Object>> dailyTokens = new ArrayList<>();
        for (int offset = 13; offset >= 0; offset--) {
            String date = today.minusDays(offset).toString();
            dailyTokens.add(dailyByDate.getOrDefault(date, Map.of(
                    "date", date, "promptTokens", 0L, "completionTokens", 0L,
                    "turns", 0L, "players", 0L)));
        }

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

        String currentModel = jdbc.query("select display_name from ai_model_config where active = true limit 1",
                (row, index) -> row.getString(1)).stream().findFirst().orElse("V4 Pro");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalUsers", totalUsers);
        result.put("usersWithCharacter", totalSaves);
        result.put("todayActive", todayActive);
        result.put("onlinePlayers", onlinePlayers == null ? 0 : onlinePlayers);
        result.put("totalTurns", turnMetrics.get("totalTurns"));
        result.put("todayTurns", turnMetrics.get("todayTurns"));
        result.put("totalTokens", turnMetrics.get("totalTokens"));
        result.put("todayTokens", turnMetrics.get("todayTokens"));
        result.put("failedToday", failedToday);
        result.put("avgLatency", Math.round(turnMetrics.get("avgLatency").doubleValue()));
        result.put("currentModel", currentModel);
        result.put("dailyTokens", dailyTokens);
        result.put("recentTurns", recentTurns);
        Map<String, Object> snapshot = Map.copyOf(result);
        dashboardCache = snapshot;
        dashboardCacheExpiresAt = nowMillis + DASHBOARD_CACHE_MILLIS;
        return snapshot;
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
            code.put("codeDisplay", label);
            code.put("plaintextAvailable", label != null);
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
        Set<String> batchCodes = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String code;
            do {
                code = "WX-" + randomCode(INVITE_CODE_LENGTH);
            } while (!batchCodes.add(code));
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

    @Transactional
    public int deleteUnusableInviteCodes() {
        List<String> unusableIds = jdbc.query("""
                SELECT ic.id
                FROM invite_code ic
                LEFT JOIN admin_invite_code known ON known.invite_code_id = ic.id
                WHERE ic.used_count = 0
                  AND known.id IS NULL
                """, (row, index) -> row.getString("id"));
        int deleted = 0;
        for (String inviteId : unusableIds) {
            jdbc.update("DELETE FROM admin_invite_code WHERE invite_code_id = ?", inviteId);
            deleted += jdbc.update("DELETE FROM invite_code WHERE id = ?", inviteId);
        }
        audit("DELETE_INVALID_GENERATED_INVITES", "删除 " + deleted + " 个错误生成且从未使用的内测码");
        return deleted;
    }

    public List<Map<String, Object>> users(String search, String characterState, String difficulty,
                                           String realmFilter, int page, int size) {
        int offset = Math.max(0, page) * Math.max(1, Math.min(size, 50));
        int limit = Math.max(1, Math.min(size, 50));

        String searchValue = search == null ? "" : search.strip();
        String stateFilter = characterState == null ? "" : characterState.strip().toUpperCase();
        String difficultyFilter = difficulty == null ? "" : difficulty.strip().toUpperCase();
        String realmFilterValue = realmFilter == null ? "" : realmFilter.strip();
        StringBuilder where = new StringBuilder(" WHERE u.status = 'ACTIVE' ");
        List<Object> args = new ArrayList<>();
        if (!searchValue.isBlank()) {
            String like = "%" + searchValue + "%";
            where.append("""
                     AND (u.email LIKE ? OR u.id LIKE ? OR coalesce(gs.id, '') LIKE ?
                          OR coalesce(JSON_UNQUOTE(JSON_EXTRACT(gs.state_json, '$.player.name')), '') LIKE ?)
                    """);
            args.add(like); args.add(like); args.add(like); args.add(like);
        }
        if ("CURRENT".equals(stateFilter)) {
            where.append(" AND gs.id IS NOT NULL ");
        } else if ("RESTARTED".equals(stateFilter)) {
            where.append(" AND gs.id IS NULL AND coalesce(arc.archive_count, 0) > 0 ");
        } else if ("NONE".equals(stateFilter)) {
            where.append(" AND gs.id IS NULL AND coalesce(arc.archive_count, 0) = 0 ");
        }
        if (!difficultyFilter.isBlank()) {
            where.append(" AND coalesce(JSON_UNQUOTE(JSON_EXTRACT(gs.state_json, '$.difficulty')), 'NORMAL') = ? ");
            args.add(difficultyFilter);
        }
        if (!realmFilterValue.isBlank()) {
            where.append(" AND coalesce(JSON_UNQUOTE(JSON_EXTRACT(gs.state_json, '$.player.realm')), '') = ? ");
            args.add(realmFilterValue);
        }

        String from = """
                FROM app_user u
                LEFT JOIN game_save gs ON gs.user_id = u.id
                LEFT JOIN (
                    SELECT user_id, count(*) archive_count
                    FROM game_life_archive GROUP BY user_id
                ) arc ON arc.user_id = u.id
                """;
        int total = jdbc.queryForObject("SELECT count(*) " + from + where, Integer.class, args.toArray());

        String sql = """
                SELECT u.id, u.email, u.registered_at, u.last_login_at,
                       gs.id AS save_id, gs.state_json, gs.updated_at AS save_updated,
                       coalesce(arc.archive_count, 0) AS archive_count
                """ + from + where + " ORDER BY u.last_login_at DESC, u.registered_at DESC LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);

        List<Map<String, Object>> rows = jdbc.query(sql, (row, idx) -> {
            String playerName = "", realm = "", race = "";
            String rowDifficulty = "NORMAL";
            int turnCount = 0;
            try {
                JsonNode state = mapper.readTree(row.getString("state_json"));
                JsonNode player = state.path("player");
                playerName = player.path("name").asText("");
                realm = player.path("realm").asText("");
                race = player.path("race").asText("");
                rowDifficulty = state.path("difficulty").asText("NORMAL");
                turnCount = state.path("turn").asInt(0);
            } catch (Exception ignored) { }
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("userId", row.getString("id"));
            user.put("email", row.getString("email"));
            user.put("rawEmail", row.getString("email"));
            user.put("registeredAt", row.getTimestamp("registered_at").toInstant().toString());
            user.put("lastLoginAt", row.getTimestamp("last_login_at") != null
                    ? row.getTimestamp("last_login_at").toInstant().toString() : null);
            user.put("saveId", row.getString("save_id"));
            user.put("playerName", playerName);
            user.put("realm", realm);
            user.put("race", race);
            user.put("difficulty", rowDifficulty);
            user.put("archiveCount", row.getInt("archive_count"));
            user.put("characterState", row.getString("save_id") == null
                    ? (row.getInt("archive_count") > 0 ? "RESTARTED" : "NONE") : "CURRENT");
            user.put("turnCount", turnCount);
            user.put("totalTokens", 0);
            user.put("saveUpdated", row.getTimestamp("save_updated") != null
                    ? row.getTimestamp("save_updated").toInstant().toString() : null);
            return user;
        }, pageArgs.toArray());

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
            u.put("email", row.getString("email"));
            u.put("status", row.getString("status"));
            u.put("registeredAt", row.getTimestamp("registered_at").toInstant().toString());
            if (row.getTimestamp("last_login_at") != null) {
                u.put("lastLoginAt", row.getTimestamp("last_login_at").toInstant().toString());
            }
            return u;
        }, userId);
        if (users.isEmpty()) return Map.of("error", "用户不存在");

        var currentLives = jdbc.query("select id, state_json, updated_at from game_save where user_id = ?",
                (row, idx) -> lifeSummary("CURRENT", row.getString("id"), row.getString("state_json"),
                        row.getTimestamp("updated_at").toInstant().toString()), userId);
        var historicalLives = jdbc.query("""
                select id, original_save_id, state_json, ended_at from game_life_archive
                where user_id = ? order by ended_at desc
                """, (row, idx) -> lifeSummary("ARCHIVED", row.getString("original_save_id"),
                row.getString("state_json"), row.getTimestamp("ended_at").toInstant().toString()), userId);

        return Map.of("user", users.getFirst(), "currentLives", currentLives,
                "historicalLives", historicalLives);
    }

    public Map<String, Object> userTurns(String userId, String saveId, int page, int size, Integer turnNumber) {
        int limit = Math.max(1, Math.min(size, 100));
        int offset = Math.max(page, 0) * limit;
        boolean current = count("select count(*) from game_save where id = ? and user_id = ?", saveId, userId) > 0;
        String table = current ? "game_turn" : "game_life_turn_archive";
        String where = current ? "save_id = ?" : "original_save_id = ?";
        if (!current && count("select count(*) from game_life_archive where original_save_id = ? and user_id = ?", saveId, userId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该角色记录不存在");
        }
        String turnFilter = turnNumber == null ? "" : " and turn_number = " + Math.max(0, turnNumber);
        int total = count("select count(*) from " + table + " where " + where + turnFilter, saveId);
        var turns = jdbc.query("""
                SELECT turn_number, player_action, prompt_tokens, completion_tokens,
                       latency_ms, model, response_id, state_delta_json, created_at
                FROM %s WHERE %s %s
                ORDER BY turn_number DESC LIMIT ? OFFSET ?
                """.formatted(table, where, turnFilter), (row, idx) -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("turn", row.getInt("turn_number"));
            t.put("action", row.getString("player_action"));
            t.put("promptTokens", row.getInt("prompt_tokens"));
            t.put("completionTokens", row.getInt("completion_tokens"));
            t.put("totalTokens", row.getInt("prompt_tokens") + row.getInt("completion_tokens"));
            t.put("latency", row.getLong("latency_ms"));
            t.put("model", row.getString("model"));
            if (row.getString("response_id") != null) {
                t.put("responseId", row.getString("response_id"));
            }
            t.put("at", row.getTimestamp("created_at").toInstant().toString());
            t.put("stateDelta", row.getString("state_delta_json"));
            t.put("status", "SUCCESS");
            return t;
        }, saveId, limit, offset);
        return Map.of("total", total, "page", page, "size", limit, "rows", turns);
    }

    public List<Map<String, Object>> executions(String saveId, int limit, Integer turnNumber) {
        String turnFilter = turnNumber == null ? "" : " and turn_number = " + Math.max(0, turnNumber);
        return jdbc.query("""
                SELECT turn_number, attempt_number, status, detail, model,
                       response_id, latency_ms, created_at
                FROM agent_execution WHERE save_id = ? %s
                ORDER BY created_at DESC LIMIT ?
                """.formatted(turnFilter), (row, idx) -> {
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
        }, saveId, Math.max(1, Math.min(limit, 200)));
    }

    private Map<String, Object> lifeSummary(String status, String saveId, String stateJson, String at) {
        Map<String, Object> life = new LinkedHashMap<>();
        life.put("status", status);
        life.put("saveId", saveId);
        life.put("at", at);
        try {
            JsonNode state = mapper.readTree(stateJson);
            JsonNode player = state.path("player");
            life.put("playerName", player.path("name").asText("尚未择定"));
            life.put("realm", player.path("realm").asText(""));
            life.put("race", player.path("race").asText(""));
            life.put("difficulty", state.path("difficulty").asText("NORMAL"));
            life.put("phase", state.path("phase").asText(""));
            life.put("turn", state.path("turn").asInt(0));
            life.put("sceneTitle", state.path("sceneTitle").asText(""));
        } catch (Exception ignored) { }
        return life;
    }

    public List<Map<String, Object>> releaseNotes() {
        return jdbc.query("""
                select r.id, r.version, r.title, r.content, r.status, r.announce_all,
                       r.published_at, r.created_at, r.updated_at,
                       (select count(*) from user_release_receipt urr where urr.release_note_id = r.id) read_count
                from release_note r order by r.created_at desc
                """, (row, idx) -> {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("id", row.getString("id"));
            note.put("version", row.getString("version"));
            note.put("title", row.getString("title"));
            note.put("content", row.getString("content"));
            note.put("status", row.getString("status"));
            note.put("announceAll", row.getBoolean("announce_all"));
            note.put("publishedAt", row.getTimestamp("published_at") == null ? null : row.getTimestamp("published_at").toInstant().toString());
            note.put("createdAt", row.getTimestamp("created_at").toInstant().toString());
            note.put("updatedAt", row.getTimestamp("updated_at").toInstant().toString());
            note.put("readCount", row.getInt("read_count"));
            return note;
        });
    }

    @Transactional
    public Map<String, Object> saveReleaseNote(String id, String version, String title, String content,
                                               String status, boolean announceAll) {
        if (version == null || version.isBlank() || title == null || title.isBlank() || content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "版本、标题和公告内容不能为空");
        }
        String safeStatus = Set.of("DRAFT", "PUBLISHED", "ARCHIVED").contains(status) ? status : "DRAFT";
        Instant now = Instant.now();
        String noteId = id == null || id.isBlank() ? "release-" + UUID.randomUUID() : id;
        Integer exists = jdbc.queryForObject("select count(*) from release_note where id = ?", Integer.class, noteId);
        if (exists != null && exists > 0) {
            jdbc.update("""
                    update release_note set version=?, title=?, content=?, status=?, announce_all=?,
                      published_at=case when ?='PUBLISHED' and published_at is null then ? else published_at end,
                      updated_at=? where id=?
                    """, version.strip(), title.strip(), content.strip(), safeStatus, announceAll,
                    safeStatus, Timestamp.from(now), Timestamp.from(now), noteId);
        } else {
            jdbc.update("""
                    insert into release_note(id, version, title, content, status, announce_all, published_at, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, noteId, version.strip(), title.strip(), content.strip(), safeStatus, announceAll,
                    "PUBLISHED".equals(safeStatus) ? Timestamp.from(now) : null, Timestamp.from(now), Timestamp.from(now));
        }
        audit("SAVE_RELEASE_NOTE", "保存版本公告 " + version + "（" + safeStatus + "）");
        return releaseNotes().stream().filter(note -> noteId.equals(note.get("id"))).findFirst().orElse(Map.of());
    }

    public List<Map<String, Object>> modelConfigs() {
        ensureModelRows();
        return jdbc.query("select model_code, display_name, secret_ciphertext, active, updated_at from ai_model_config order by model_code desc",
                (row, idx) -> Map.<String, Object>of(
                        "modelCode", row.getString("model_code"),
                        "displayName", row.getString("display_name"),
                        "keyConfigured", row.getString("secret_ciphertext") != null && !row.getString("secret_ciphertext").isBlank(),
                        "active", row.getBoolean("active"),
                        "updatedAt", row.getTimestamp("updated_at").toInstant().toString()));
    }

    @Transactional
    public void updateModelConfig(String modelCode, boolean active, String apiKey) {
        if (!Set.of("deepseek-v4-pro", "deepseek-v4-flash").contains(modelCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的模型");
        }
        ensureModelRows();
        if (active) jdbc.update("update ai_model_config set active = false");
        if (apiKey != null && !apiKey.isBlank()) {
            jdbc.update("update ai_model_config set secret_ciphertext=?, active=?, updated_at=? where model_code=?",
                    SecretCipher.encrypt(apiKey.strip(), aiConfigMasterKey), active, Timestamp.from(Instant.now()), modelCode);
        } else {
            jdbc.update("update ai_model_config set active=?, updated_at=? where model_code=?",
                    active, Timestamp.from(Instant.now()), modelCode);
        }
        audit("UPDATE_MODEL", "切换生成模型为 " + modelCode + (apiKey == null || apiKey.isBlank() ? "" : "，并更新密钥"));
    }

    private void ensureModelRows() {
        Instant now = Instant.now();
        jdbc.update("insert ignore into ai_model_config(model_code, display_name, active, updated_at) values ('deepseek-v4-pro','V4 Pro',true,?)", Timestamp.from(now));
        jdbc.update("insert ignore into ai_model_config(model_code, display_name, active, updated_at) values ('deepseek-v4-flash','V4 Flash',false,?)", Timestamp.from(now));
    }

    public List<Map<String, Object>> operationLogs(int limit) {
        return jdbc.query("select operation, detail, operated_at from admin_operation_log order by operated_at desc limit ?",
                (row, idx) -> Map.<String, Object>of(
                        "operation", row.getString("operation"),
                        "detail", row.getString("detail"),
                        "at", row.getTimestamp("operated_at").toInstant().toString()),
                Math.max(1, Math.min(limit, 200)));
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
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(INVITE_CODE_ALPHABET[random.nextInt(INVITE_CODE_ALPHABET.length)]);
        }
        return code.toString();
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

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}

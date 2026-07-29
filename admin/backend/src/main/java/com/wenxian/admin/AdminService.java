package com.wenxian.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
    private final BCryptPasswordEncoder playerPasswordEncoder = new BCryptPasswordEncoder(10);
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
        ensureGameplayConfig("account_public_turn_limit", "20");
        ensureGameplayConfig("custom_api_enabled", "true");
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
                       coalesce(sum(case when model like '%@USER' then 1 else 0 end), 0) custom_turns,
                       coalesce(sum(case when model not like '%@USER' then 1 else 0 end), 0) platform_turns,
                       coalesce(sum(case when created_at >= ? and model like '%@USER' then 1 else 0 end), 0) today_custom_turns,
                       coalesce(sum(case when created_at >= ? and model not like '%@USER' then 1 else 0 end), 0) today_platform_turns,
                       coalesce(avg(case when latency_ms > 0 then latency_ms end), 0) avg_latency
                FROM (
                    SELECT created_at, prompt_tokens, completion_tokens, latency_ms, model FROM game_turn
                    UNION ALL
                    SELECT created_at, prompt_tokens, completion_tokens, latency_ms, model FROM game_life_turn_archive
                ) all_turns
                """, (row, index) -> Map.of(
                "totalTurns", row.getLong("total_turns"),
                "todayTurns", row.getLong("today_turns"),
                "customTurns", row.getLong("custom_turns"),
                "platformTurns", row.getLong("platform_turns"),
                "todayCustomTurns", row.getLong("today_custom_turns"),
                "todayPlatformTurns", row.getLong("today_platform_turns"),
                "avgLatency", row.getDouble("avg_latency")
        ), todayStart, todayStart, todayStart);

        Map<String, Number> usageMetrics = jdbc.queryForObject("""
                SELECT count(*) request_count,
                       coalesce(sum(case when status = 'FAILED' then 1 else 0 end), 0) failed_count,
                       coalesce(sum(case when credential_source = 'PUBLIC' then prompt_tokens else 0 end), 0) platform_prompt,
                       coalesce(sum(case when credential_source = 'PUBLIC' then completion_tokens else 0 end), 0) platform_completion,
                       coalesce(sum(case when credential_source = 'PUBLIC' then cache_hit_tokens else 0 end), 0) platform_cache_hit,
                       coalesce(sum(case when credential_source = 'PUBLIC' then cache_miss_tokens else 0 end), 0) platform_cache_miss,
                       coalesce(sum(case when credential_source <> 'PUBLIC' then prompt_tokens else 0 end), 0) custom_prompt,
                       coalesce(sum(case when credential_source <> 'PUBLIC' then completion_tokens else 0 end), 0) custom_completion
                FROM ai_usage_log WHERE created_at >= ?
                """, (row, index) -> Map.of(
                "requests", row.getLong("request_count"),
                "failures", row.getLong("failed_count"),
                "platformPrompt", row.getLong("platform_prompt"),
                "platformCompletion", row.getLong("platform_completion"),
                "platformCacheHit", row.getLong("platform_cache_hit"),
                "platformCacheMiss", row.getLong("platform_cache_miss"),
                "customPrompt", row.getLong("custom_prompt"),
                "customCompletion", row.getLong("custom_completion")
        ), todayStart);
        Map<String, Number> totalUsageMetrics = jdbc.queryForObject("""
                SELECT coalesce(sum(case when credential_source = 'PUBLIC'
                           then prompt_tokens + completion_tokens else 0 end), 0) platform_tokens,
                       coalesce(sum(case when credential_source <> 'PUBLIC'
                           then prompt_tokens + completion_tokens else 0 end), 0) custom_tokens
                FROM ai_usage_log
                """, (row, index) -> Map.of(
                "platformTokens", row.getLong("platform_tokens"),
                "customTokens", row.getLong("custom_tokens")
        ));
        Timestamp trackingStartedAt = jdbc.queryForObject(
                "SELECT min(created_at) FROM ai_usage_log", Timestamp.class);

        Timestamp tokenSince = Timestamp.from(Instant.now().minus(Duration.ofDays(14)));
        List<Map<String, Object>> rawDailyTokens = jdbc.query("""
                SELECT DATE(created_at) usage_date,
                       coalesce(sum(case when credential_source = 'PUBLIC' then prompt_tokens else 0 end), 0) platform_prompt,
                       coalesce(sum(case when credential_source = 'PUBLIC' then completion_tokens else 0 end), 0) platform_completion,
                       coalesce(sum(case when credential_source <> 'PUBLIC' then prompt_tokens else 0 end), 0) custom_prompt,
                       coalesce(sum(case when credential_source <> 'PUBLIC' then completion_tokens else 0 end), 0) custom_completion,
                       coalesce(sum(cache_hit_tokens), 0) cache_hit,
                       coalesce(sum(cache_miss_tokens), 0) cache_miss,
                       count(*) request_count,
                       coalesce(sum(case when status = 'FAILED' then 1 else 0 end), 0) failure_count,
                       coalesce(sum(case when operation = 'LEGACY_GAMEPLAY' then 1 else 0 end), 0) estimated_count,
                       group_concat(distinct concat(provider, ' / ', substring_index(model, '@', 1)) order by provider, model separator ', ') models,
                       count(distinct save_id) player_count
                FROM ai_usage_log
                WHERE created_at >= ?
                GROUP BY DATE(created_at)
                ORDER BY usage_date
                """, (row, index) -> Map.<String, Object>ofEntries(
                Map.entry("date", row.getDate("usage_date").toLocalDate().toString()),
                Map.entry("platformPromptTokens", row.getLong("platform_prompt")),
                Map.entry("platformCompletionTokens", row.getLong("platform_completion")),
                Map.entry("customPromptTokens", row.getLong("custom_prompt")),
                Map.entry("customCompletionTokens", row.getLong("custom_completion")),
                Map.entry("cacheHitTokens", row.getLong("cache_hit")),
                Map.entry("cacheMissTokens", row.getLong("cache_miss")),
                Map.entry("requests", row.getLong("request_count")),
                Map.entry("failures", row.getLong("failure_count")),
                Map.entry("estimatedRequests", row.getLong("estimated_count")),
                Map.entry("models", row.getString("models") == null ? "" : row.getString("models")),
                Map.entry("players", row.getLong("player_count"))
        ), tokenSince);
        Map<String, Map<String, Object>> dailyByDate = new LinkedHashMap<>();
        rawDailyTokens.forEach(day -> dailyByDate.put(String.valueOf(day.get("date")), new LinkedHashMap<>(day)));
        List<Map<String, Object>> rawDailyTurns = jdbc.query("""
                SELECT DATE(created_at) usage_date,
                       coalesce(sum(case when model like '%@USER' then 1 else 0 end), 0) custom_turns,
                       coalesce(sum(case when model not like '%@USER' then 1 else 0 end), 0) platform_turns
                FROM (
                    SELECT created_at, model FROM game_turn WHERE created_at >= ?
                    UNION ALL
                    SELECT created_at, model FROM game_life_turn_archive WHERE created_at >= ?
                ) turn_usage
                GROUP BY DATE(created_at)
                """, (row, index) -> Map.<String, Object>of(
                "date", row.getDate("usage_date").toLocalDate().toString(),
                "platformTurns", row.getLong("platform_turns"),
                "customTurns", row.getLong("custom_turns")
        ), tokenSince, tokenSince);
        rawDailyTurns.forEach(turns -> {
            String date = String.valueOf(turns.get("date"));
            Map<String, Object> day = dailyByDate.computeIfAbsent(date, ignored -> emptyDailyUsage(date));
            day.put("platformTurns", turns.get("platformTurns"));
            day.put("customTurns", turns.get("customTurns"));
        });
        List<Map<String, Object>> dailyTokens = new ArrayList<>();
        for (int offset = 13; offset >= 0; offset--) {
            String date = today.minusDays(offset).toString();
            Map<String, Object> day = dailyByDate.getOrDefault(date, emptyDailyUsage(date));
            day.putIfAbsent("platformTurns", 0L);
            day.putIfAbsent("customTurns", 0L);
            dailyTokens.add(day);
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
                    "at", displayTime(row.getTimestamp("created_at"))
            );
        });

        List<Map<String, Object>> modelUsage = jdbc.query("""
                SELECT provider,
                       substring_index(model, '@', 1) model_name,
                       credential_source,
                       count(*) request_count,
                       coalesce(sum(prompt_tokens), 0) prompt_tokens,
                       coalesce(sum(completion_tokens), 0) completion_tokens,
                       coalesce(sum(cache_hit_tokens), 0) cache_hit_tokens,
                       coalesce(sum(cache_miss_tokens), 0) cache_miss_tokens,
                       coalesce(sum(case when status = 'FAILED' then 1 else 0 end), 0) failure_count,
                       coalesce(avg(case when latency_ms > 0 then latency_ms end), 0) avg_latency,
                       min(created_at) first_used_at,
                       max(created_at) last_used_at
                FROM ai_usage_log
                GROUP BY provider, substring_index(model, '@', 1), credential_source
                ORDER BY last_used_at DESC
                """, (row, index) -> Map.<String, Object>ofEntries(
                Map.entry("provider", row.getString("provider")),
                Map.entry("model", row.getString("model_name")),
                Map.entry("source", row.getString("credential_source")),
                Map.entry("requests", row.getLong("request_count")),
                Map.entry("promptTokens", row.getLong("prompt_tokens")),
                Map.entry("completionTokens", row.getLong("completion_tokens")),
                Map.entry("cacheHitTokens", row.getLong("cache_hit_tokens")),
                Map.entry("cacheMissTokens", row.getLong("cache_miss_tokens")),
                Map.entry("failures", row.getLong("failure_count")),
                Map.entry("avgLatency", Math.round(row.getDouble("avg_latency"))),
                Map.entry("firstUsedAt", displayTime(row.getTimestamp("first_used_at"))),
                Map.entry("lastUsedAt", displayTime(row.getTimestamp("last_used_at")))
        ));

        String currentModel = jdbc.query("select display_name from ai_model_config where active = true limit 1",
                (row, index) -> row.getString(1)).stream().findFirst().orElse("V4 Pro");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalUsers", totalUsers);
        result.put("usersWithCharacter", totalSaves);
        result.put("todayActive", todayActive);
        result.put("onlinePlayers", onlinePlayers == null ? 0 : onlinePlayers);
        result.put("totalTurns", turnMetrics.get("totalTurns"));
        result.put("todayTurns", turnMetrics.get("todayTurns"));
        result.put("totalPlatformTurns", turnMetrics.get("platformTurns"));
        result.put("totalCustomTurns", turnMetrics.get("customTurns"));
        result.put("todayPlatformTurns", turnMetrics.get("todayPlatformTurns"));
        result.put("todayCustomTurns", turnMetrics.get("todayCustomTurns"));
        result.put("todayAiRequests", usageMetrics.get("requests"));
        result.put("todayAiFailures", usageMetrics.get("failures"));
        result.put("todayPlatformPromptTokens", usageMetrics.get("platformPrompt"));
        result.put("todayPlatformCompletionTokens", usageMetrics.get("platformCompletion"));
        result.put("todayPlatformCacheHitTokens", usageMetrics.get("platformCacheHit"));
        result.put("todayPlatformCacheMissTokens", usageMetrics.get("platformCacheMiss"));
        result.put("todayCustomPromptTokens", usageMetrics.get("customPrompt"));
        result.put("todayCustomCompletionTokens", usageMetrics.get("customCompletion"));
        result.put("totalPlatformTokens", totalUsageMetrics.get("platformTokens"));
        result.put("totalCustomTokens", totalUsageMetrics.get("customTokens"));
        result.put("usageTrackingStartedAt", trackingStartedAt == null ? "" : displayTime(trackingStartedAt));
        result.put("avgLatency", Math.round(turnMetrics.get("avgLatency").doubleValue()));
        result.put("currentModel", currentModel);
        result.put("customApiUsers", count("SELECT count(*) FROM user_ai_credential WHERE status = 'ACTIVE'"));
        result.put("todayPublicInvocations", count("SELECT coalesce(sum(used_count), 0) FROM user_daily_quota WHERE usage_date = ?", java.sql.Date.valueOf(today)));
        result.put("todayCustomTurns", count("""
                SELECT count(*) FROM (
                    SELECT model, created_at FROM game_turn
                    UNION ALL
                    SELECT model, created_at FROM game_life_turn_archive
                ) custom_turns WHERE created_at >= ? AND model LIKE '%@USER'
                """, todayStart));
        result.put("dailyTokens", dailyTokens);
        result.put("modelUsage", modelUsage);
        result.put("recentTurns", recentTurns);
        Map<String, Object> snapshot = Map.copyOf(result);
        dashboardCache = snapshot;
        dashboardCacheExpiresAt = nowMillis + DASHBOARD_CACHE_MILLIS;
        return snapshot;
    }

    public Map<String, Object> gameplayConfig() {
        int accountLimit = parsePositiveInt(configValue("account_public_turn_limit", "20"), 20);
        boolean customEnabled = Boolean.parseBoolean(configValue("custom_api_enabled", "true"));
        ZoneId reportingZone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(reportingZone);
        Timestamp todayStart = Timestamp.from(today.atStartOfDay(reportingZone).toInstant());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountPublicTurnLimit", accountLimit);
        result.put("customApiEnabled", customEnabled);
        result.put("customApiUsers", count("SELECT count(*) FROM user_ai_credential WHERE status = 'ACTIVE'"));
        result.put("invalidCredentialUsers", count("SELECT count(*) FROM user_ai_credential WHERE status <> 'ACTIVE'"));
        result.put("todayPublicInvocations", count("SELECT coalesce(sum(used_count), 0) FROM user_daily_quota WHERE usage_date = ?", java.sql.Date.valueOf(today)));
        result.put("todayCustomTurns", count("""
                SELECT count(*) FROM (
                    SELECT model, created_at FROM game_turn
                    UNION ALL
                    SELECT model, created_at FROM game_life_turn_archive
                ) custom_turns WHERE created_at >= ? AND model LIKE '%@USER'
                """, todayStart));
        return result;
    }

    @Transactional
    public Map<String, Object> updateGameplayConfig(int accountPublicTurnLimit, boolean customApiEnabled) {
        if (accountPublicTurnLimit < 1 || accountPublicTurnLimit > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号赠送回合需在 1-1000 之间");
        }
        putGameplayConfig("account_public_turn_limit", String.valueOf(accountPublicTurnLimit));
        putGameplayConfig("custom_api_enabled", String.valueOf(customApiEnabled));
        dashboardCache = null;
        audit("UPDATE_GAMEPLAY_CONFIG", "账号赠送回合调整为 " + accountPublicTurnLimit
                + " 回；玩家自备密钥" + (customApiEnabled ? "已开启" : "已关闭"));
        return gameplayConfig();
    }

    public Map<String, Object> registrationConfig() {
        int maxUsers = parsePositiveInt(configValue("registration_max_users", "1000"), 1000);
        long currentUsers = count("select count(*) from app_user");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maxUsers", maxUsers);
        result.put("currentUsers", currentUsers);
        result.put("remainingSlots", Math.max(0, maxUsers - currentUsers));
        result.put("limitMessage", configValue("registration_limit_message", "当前系统试玩人数较多，暂时停止新账号注册。"));
        result.put("contactText", configValue("registration_contact_text", "如需参与测试或反馈问题，请加入QQ群：1092478336。"));
        return result;
    }

    @Transactional
    public Map<String, Object> updateRegistrationConfig(int maxUsers, String limitMessage, String contactText) {
        if (maxUsers < 1 || maxUsers > 100000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最大注册数需在 1-100000 之间");
        }
        String safeMessage = requiredConfigText(limitMessage, "满员提示", 220);
        String safeContact = requiredConfigText(contactText, "联系说明", 220);
        putGameplayConfig("registration_max_users", String.valueOf(maxUsers));
        putGameplayConfig("registration_limit_message", safeMessage);
        putGameplayConfig("registration_contact_text", safeContact);
        audit("UPDATE_REGISTRATION_CONFIG", "最大注册数调整为 " + maxUsers + "；更新满员提示与联系说明");
        return registrationConfig();
    }

    private String requiredConfigText(String value, String label, int maxLength) {
        String safe = value == null ? "" : value.strip();
        if (safe.isBlank() || safe.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "需为 1-" + maxLength + " 个字符");
        }
        return safe;
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
                code.put("expiresAt", displayTime(row.getTimestamp("expires_at")));
            }
            code.put("createdAt", displayTime(row.getTimestamp("created_at")));
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
            user.put("registeredAt", displayTime(row.getTimestamp("registered_at")));
            user.put("lastLoginAt", row.getTimestamp("last_login_at") != null
                    ? displayTime(row.getTimestamp("last_login_at")) : null);
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
                    ? displayTime(row.getTimestamp("save_updated")) : null);
            return user;
        }, pageArgs.toArray());

        return List.of(
                Map.<String, Object>of("total", total, "page", page, "size", limit),
                Map.<String, Object>of("rows", rows)
        );
    }

    public Map<String, Object> userDetail(String userId) {
        var users = jdbc.query("""
                SELECT u.id, u.email, u.status, u.registered_at, u.last_login_at,
                       u.password_hash, u.password_set_at, u.registration_ip_hash,
                       c.masked_suffix, c.status credential_status, c.last_verified_at
                FROM app_user u
                LEFT JOIN user_ai_credential c ON c.user_id=u.id
                WHERE u.id = ?
                """, (row, idx) -> {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("userId", row.getString("id"));
            u.put("email", row.getString("email"));
            u.put("status", row.getString("status"));
            u.put("registeredAt", displayTime(row.getTimestamp("registered_at")));
            if (row.getTimestamp("last_login_at") != null) {
                u.put("lastLoginAt", displayTime(row.getTimestamp("last_login_at")));
            }
            u.put("passwordConfigured", row.getString("password_hash") != null);
            if (row.getTimestamp("password_set_at") != null) {
                u.put("passwordSetAt", displayTime(row.getTimestamp("password_set_at")));
            }
            String ipHash = row.getString("registration_ip_hash");
            u.put("registrationNetwork", ipHash == null ? null : "…" + ipHash.substring(Math.max(0, ipHash.length() - 8)));
            u.put("credentialConfigured", row.getString("credential_status") != null);
            u.put("credentialStatus", row.getString("credential_status"));
            u.put("credentialSuffix", row.getString("masked_suffix"));
            if (row.getTimestamp("last_verified_at") != null) {
                u.put("credentialVerifiedAt", displayTime(row.getTimestamp("last_verified_at")));
            }
            return u;
        }, userId);
        if (users.isEmpty()) return Map.of("error", "用户不存在");

        var currentLives = jdbc.query("select id, state_json, updated_at from game_save where user_id = ?",
                (row, idx) -> lifeSummary("CURRENT", row.getString("id"), row.getString("state_json"),
                        displayTime(row.getTimestamp("updated_at"))), userId);
        var historicalLives = jdbc.query("""
                select id, original_save_id, state_json, ended_at from game_life_archive
                where user_id = ? order by ended_at desc
                """, (row, idx) -> lifeSummary("ARCHIVED", row.getString("original_save_id"),
                row.getString("state_json"), displayTime(row.getTimestamp("ended_at"))), userId);

        return Map.of("user", users.getFirst(), "currentLives", currentLives,
                "historicalLives", historicalLives);
    }

    @Transactional
    public String resetPlayerPassword(String userId) {
        String temporaryPassword = randomCode(12);
        int updated = jdbc.update("update app_user set password_hash=?, password_set_at=? where id=? and status='ACTIVE'",
                playerPasswordEncoder.encode(temporaryPassword), Timestamp.from(Instant.now()), userId);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        jdbc.update("update user_session set revoked_at=? where user_id=? and revoked_at is null",
                Timestamp.from(Instant.now()), userId);
        audit("RESET_PLAYER_PASSWORD", "为用户 " + userId + " 生成临时密码并退出旧会话");
        return temporaryPassword;
    }

    @Transactional
    public void unlockPlayerAuth(String userId) {
        String email = jdbc.query("select email from app_user where id=?",
                result -> result.next() ? result.getString(1) : null, userId);
        if (email == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        jdbc.update("delete from auth_login_failure where email_hash=?", sha256(email.toLowerCase()));
        jdbc.update("delete from auth_registration where user_id=?", userId);
        audit("UNLOCK_PLAYER_AUTH", "清除用户 " + userId + " 的登录失败与注册网络限制");
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
            t.put("at", displayTime(row.getTimestamp("created_at")));
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
            e.put("at", displayTime(row.getTimestamp("created_at")));
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
                select r.id, r.version, r.title, r.content, r.status, r.announce_all, r.show_on_login,
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
            note.put("showOnLogin", row.getBoolean("show_on_login"));
            note.put("publishedAt", row.getTimestamp("published_at") == null ? null : displayTime(row.getTimestamp("published_at")));
            note.put("createdAt", displayTime(row.getTimestamp("created_at")));
            note.put("updatedAt", displayTime(row.getTimestamp("updated_at")));
            note.put("readCount", row.getInt("read_count"));
            return note;
        });
    }

    @Transactional
    public Map<String, Object> saveReleaseNote(String id, String version, String title, String content,
                                               String status, boolean announceAll, boolean showOnLogin) {
        if (version == null || version.isBlank() || title == null || title.isBlank() || content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "版本、标题和公告内容不能为空");
        }
        String safeStatus = Set.of("DRAFT", "PUBLISHED", "ARCHIVED").contains(status) ? status : "DRAFT";
        Instant now = Instant.now();
        String noteId = id == null || id.isBlank() ? "release-" + UUID.randomUUID() : id;
        boolean effectiveShowOnLogin = "PUBLISHED".equals(safeStatus) && announceAll && showOnLogin;
        if (effectiveShowOnLogin) {
            jdbc.update("update release_note set show_on_login=false where id<>?", noteId);
        }
        Integer exists = jdbc.queryForObject("select count(*) from release_note where id = ?", Integer.class, noteId);
        if (exists != null && exists > 0) {
            jdbc.update("""
                    update release_note set version=?, title=?, content=?, status=?, announce_all=?, show_on_login=?,
                      published_at=case when ?='PUBLISHED' and published_at is null then ? else published_at end,
                      updated_at=? where id=?
                    """, version.strip(), title.strip(), content.strip(), safeStatus, announceAll, effectiveShowOnLogin,
                    safeStatus, Timestamp.from(now), Timestamp.from(now), noteId);
        } else {
            jdbc.update("""
                    insert into release_note(id, version, title, content, status, announce_all, show_on_login, published_at, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, noteId, version.strip(), title.strip(), content.strip(), safeStatus, announceAll, effectiveShowOnLogin,
                    "PUBLISHED".equals(safeStatus) ? Timestamp.from(now) : null, Timestamp.from(now), Timestamp.from(now));
        }
        audit("SAVE_RELEASE_NOTE", "保存版本公告 " + version + "（" + safeStatus + "）");
        return releaseNotes().stream().filter(note -> noteId.equals(note.get("id"))).findFirst().orElse(Map.of());
    }

    public List<Map<String, Object>> modelConfigs() {
        ensureModelRows();
        return jdbc.query("select provider_code, model_code, display_name, secret_ciphertext, active, updated_at from ai_model_config order by provider_code, model_code desc",
                (row, idx) -> Map.<String, Object>of(
                        "providerCode", row.getString("provider_code"),
                        "modelCode", row.getString("model_code"),
                        "displayName", row.getString("display_name"),
                        "keyConfigured", row.getString("secret_ciphertext") != null && !row.getString("secret_ciphertext").isBlank(),
                        "active", row.getBoolean("active"),
                        "updatedAt", displayTime(row.getTimestamp("updated_at"))));
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
        jdbc.update("insert ignore into ai_model_config(provider_code, model_code, display_name, active, updated_at) values ('DEEPSEEK','deepseek-v4-pro','V4 Pro',true,?)", Timestamp.from(now));
        jdbc.update("insert ignore into ai_model_config(provider_code, model_code, display_name, active, updated_at) values ('DEEPSEEK','deepseek-v4-flash','V4 Flash',false,?)", Timestamp.from(now));
    }

    public List<Map<String, Object>> operationLogs(int limit) {
        return jdbc.query("select operation, detail, operated_at from admin_operation_log order by operated_at desc limit ?",
                (row, idx) -> Map.<String, Object>of(
                        "operation", row.getString("operation"),
                        "detail", row.getString("detail"),
                        "at", displayTime(row.getTimestamp("operated_at"))),
                Math.max(1, Math.min(limit, 200)));
    }

    // ---- helpers ----

    private static String displayTime(Timestamp timestamp) {
        if (timestamp == null) return null;
        return timestamp.toLocalDateTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toString();
    }

    private static Map<String, Object> emptyDailyUsage(String date) {
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", date);
        day.put("platformPromptTokens", 0L);
        day.put("platformCompletionTokens", 0L);
        day.put("customPromptTokens", 0L);
        day.put("customCompletionTokens", 0L);
        day.put("cacheHitTokens", 0L);
        day.put("cacheMissTokens", 0L);
        day.put("requests", 0L);
        day.put("failures", 0L);
        day.put("estimatedRequests", 0L);
        day.put("models", "");
        day.put("players", 0L);
        day.put("platformTurns", 0L);
        day.put("customTurns", 0L);
        return day;
    }

    private void ensureGameplayConfig(String key, String value) {
        if (count("SELECT count(*) FROM gameplay_config WHERE config_key = ?", key) == 0) {
            try {
                jdbc.update("INSERT INTO gameplay_config(config_key, config_value, updated_at) VALUES (?, ?, ?)",
                        key, value, Timestamp.from(Instant.now()));
            } catch (org.springframework.dao.DuplicateKeyException ignored) { }
        }
    }

    private void putGameplayConfig(String key, String value) {
        int updated = jdbc.update("UPDATE gameplay_config SET config_value = ?, updated_at = ? WHERE config_key = ?",
                value, Timestamp.from(Instant.now()), key);
        if (updated == 0) ensureGameplayConfig(key, value);
    }

    private String configValue(String key, String fallback) {
        return jdbc.query("SELECT config_value FROM gameplay_config WHERE config_key = ?",
                (row, index) -> row.getString(1), key).stream().findFirst().orElse(fallback);
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

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

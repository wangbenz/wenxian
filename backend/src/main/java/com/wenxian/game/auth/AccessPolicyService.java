package com.wenxian.game.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 账号访问策略的唯一判定入口。
 *
 * <p>正式环境默认读取数据库中的开放/白名单配置。独立灰度进程可通过外部配置启用
 * 进程级账号名单；启用后完全忽略共享数据库的正式访问模式，避免灰度开关误伤线上玩家。
 * 该服务只决定注册、登录和既有会话是否可继续，不创建或修改玩家账号。</p>
 */
@Service
public class AccessPolicyService {
    public static final String MODE_OPEN = "OPEN";
    public static final String MODE_WHITELIST = "WHITELIST";
    private static final String DEFAULT_TITLE = "命卷暂时封存";
    private static final String DEFAULT_MESSAGE = "游戏正在更新中，请稍后再回来。";
    private static final String DEFAULT_ISOLATED_TITLE = "当前版本正在受邀测试";
    private static final String DEFAULT_ISOLATED_MESSAGE = "当前版本仅向受邀账号开放，请使用收到的测试账号进入。";

    private final JdbcTemplate jdbc;
    private final boolean isolatedAccessEnabled;
    private final Set<String> isolatedAllowedEmails;
    private final String isolatedTitle;
    private final String isolatedMessage;
    private final String isolatedResumeAt;

    @Autowired
    public AccessPolicyService(
            JdbcTemplate jdbc,
            @Value("${game.auth.isolated-access.enabled:false}") boolean isolatedAccessEnabled,
            @Value("${game.auth.isolated-access.allowed-emails:}") String isolatedAllowedEmails,
            @Value("${game.auth.isolated-access.title:" + DEFAULT_ISOLATED_TITLE + "}") String isolatedTitle,
            @Value("${game.auth.isolated-access.message:" + DEFAULT_ISOLATED_MESSAGE + "}") String isolatedMessage,
            @Value("${game.auth.isolated-access.resume-at:}") String isolatedResumeAt) {
        this.jdbc = jdbc;
        this.isolatedAccessEnabled = isolatedAccessEnabled;
        this.isolatedAllowedEmails = parseEmails(isolatedAllowedEmails);
        this.isolatedTitle = text(isolatedTitle, DEFAULT_ISOLATED_TITLE);
        this.isolatedMessage = text(isolatedMessage, DEFAULT_ISOLATED_MESSAGE);
        this.isolatedResumeAt = isolatedResumeAt == null ? "" : isolatedResumeAt.strip();
        if (isolatedAccessEnabled && this.isolatedAllowedEmails.isEmpty()) {
            throw new IllegalStateException("独立访问限制已开启，但没有配置任何允许账号");
        }
    }

    /** 兼容单元测试和未启用独立灰度访问的轻量构造。 */
    public AccessPolicyService(JdbcTemplate jdbc) {
        this(jdbc, false, "", DEFAULT_ISOLATED_TITLE, DEFAULT_ISOLATED_MESSAGE, "");
    }

    @PostConstruct
    void ensureDefaults() {
        Timestamp now = Timestamp.from(Instant.now());
        putIfAbsent("game_access_mode", MODE_OPEN, now);
        putIfAbsent("game_access_notice_title", DEFAULT_TITLE, now);
        putIfAbsent("game_access_notice_content", DEFAULT_MESSAGE, now);
        putIfAbsent("game_access_resume_at", "", now);
    }

    public AccessStatus status() {
        if (isolatedAccessEnabled) {
            return new AccessStatus(true, isolatedTitle, isolatedMessage, isolatedResumeAt);
        }
        Map<String, String> values = new LinkedHashMap<>();
        jdbc.query("""
                select config_key, config_value from gameplay_config
                where config_key in ('game_access_mode','game_access_notice_title',
                                     'game_access_notice_content','game_access_resume_at')
                """, (row, index) -> Map.entry(row.getString(1), row.getString(2)))
                .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
        boolean restricted = MODE_WHITELIST.equals(values.get("game_access_mode"));
        return new AccessStatus(restricted,
                values.getOrDefault("game_access_notice_title", DEFAULT_TITLE),
                values.getOrDefault("game_access_notice_content", DEFAULT_MESSAGE),
                values.getOrDefault("game_access_resume_at", ""));
    }

    public void assertAllowed(String userId) {
        if (isolatedAccessEnabled) {
            String email = jdbc.query("select email from app_user where id=? and status='ACTIVE'",
                    (row, index) -> row.getString(1), userId).stream().findFirst().orElse("");
            if (isolatedAllowedEmails.contains(normalizeEmail(email))) return;
            throw new GameMaintenanceException(isolatedTitle, isolatedMessage, isolatedResumeAt);
        }
        AccessStatus status = status();
        if (!status.restricted()) return;
        Integer count = jdbc.queryForObject(
                "select count(*) from game_access_whitelist where user_id=?", Integer.class, userId);
        if (count != null && count > 0) return;
        throw new GameMaintenanceException(status.title(), status.message(), status.resumeAt());
    }

    public void assertRegistrationAllowed() {
        AccessStatus status = status();
        if (!status.restricted()) return;
        throw new GameMaintenanceException(status.title(), status.message(), status.resumeAt());
    }

    private void putIfAbsent(String key, String value, Timestamp now) {
        Integer count = jdbc.queryForObject(
                "select count(*) from gameplay_config where config_key=?", Integer.class, key);
        if (count != null && count > 0) return;
        try {
            jdbc.update("insert into gameplay_config(config_key,config_value,updated_at) values (?,?,?)",
                    key, value, now);
        } catch (DataAccessException error) {
            Integer existing = jdbc.queryForObject(
                    "select count(*) from gameplay_config where config_key=?", Integer.class, key);
            if (existing == null || existing == 0) throw error;
        }
    }

    private static Set<String> parseEmails(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(AccessPolicyService::normalizeEmail)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    public record AccessStatus(boolean restricted, String title, String message, String resumeAt) {}
}

package com.wenxian.game.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AccessPolicyService {
    public static final String MODE_OPEN = "OPEN";
    public static final String MODE_WHITELIST = "WHITELIST";
    private static final String DEFAULT_TITLE = "命卷暂时封存";
    private static final String DEFAULT_MESSAGE = "游戏正在更新中，请稍后再回来。";

    private final JdbcTemplate jdbc;

    public AccessPolicyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    public record AccessStatus(boolean restricted, String title, String message, String resumeAt) {}
}

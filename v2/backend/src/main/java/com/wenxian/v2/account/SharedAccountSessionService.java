package com.wenxian.v2.account;

import com.wenxian.v2.api.V2ApiException;
import com.wenxian.v2.config.V2Properties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class SharedAccountSessionService {
    public static final String COOKIE_NAME = "WENXIAN_SESSION";

    private final JdbcTemplate jdbc;
    private final V2Properties properties;

    public SharedAccountSessionService(JdbcTemplate jdbc, V2Properties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public Account requireAccount(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw expired();
        Instant now = Instant.now();
        Account account = jdbc.query("""
                        select u.id, u.email
                        from user_session s
                        join app_user u on u.id = s.user_id
                        where s.token_hash=? and s.revoked_at is null and s.expires_at>?
                          and s.created_at>? and u.status='ACTIVE'
                        """,
                (row, index) -> new Account(row.getString("id"), row.getString("email")),
                sha256(rawToken), Timestamp.from(now),
                Timestamp.from(now.minus(Duration.ofHours(properties.sessionIdleHours()))))
                .stream().findFirst().orElseThrow(this::expired);
        assertAccessAllowed(account.userId());
        return account;
    }

    private void assertAccessAllowed(String userId) {
        String mode = jdbc.query("select config_value from gameplay_config where config_key='game_access_mode'",
                (row, index) -> row.getString(1)).stream().findFirst().orElse("OPEN");
        if (!"WHITELIST".equals(mode)) return;
        Integer allowed = jdbc.queryForObject(
                "select count(*) from game_access_whitelist where user_id=?", Integer.class, userId);
        if (allowed != null && allowed > 0) return;
        String message = jdbc.query("select config_value from gameplay_config where config_key='game_access_notice_content'",
                (row, index) -> row.getString(1)).stream().findFirst().orElse("游戏正在更新中，请稍后再回来。");
        throw new V2ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GAME_UPDATING", "ACCESS", message,
                List.of("稍后重新进入游戏"));
    }

    private V2ApiException expired() {
        return new V2ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "ACCOUNT",
                "登录状态已经失效，请重新登录。", List.of("返回登录页"));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record Account(String userId, String email) {
    }
}

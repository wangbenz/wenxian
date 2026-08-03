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
    private final V2AccessPolicyService accessPolicy;

    public SharedAccountSessionService(JdbcTemplate jdbc, V2Properties properties,
                                       V2AccessPolicyService accessPolicy) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.accessPolicy = accessPolicy;
    }

    public Account requireAccount(String rawToken) {
        Account account = currentAccount(rawToken);
        if (account == null) throw expired();
        return account;
    }

    public Account currentAccount(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;
        Instant now = Instant.now();
        Account account = jdbc.query("""
                        select u.id,u.email,u.password_hash
                        from user_session s
                        join app_user u on u.id = s.user_id
                        where s.token_hash=? and s.revoked_at is null and s.expires_at>?
                          and s.created_at>? and u.status='ACTIVE'
                        """,
                (row, index) -> new Account(row.getString("id"), row.getString("email"),
                        row.getString("password_hash") != null),
                sha256(rawToken), Timestamp.from(now),
                Timestamp.from(now.minus(Duration.ofHours(properties.sessionIdleHours()))))
                .stream().findFirst().orElse(null);
        if (account == null) return null;
        accessPolicy.assertAllowed(account.userId());
        jdbc.update("update user_session set created_at=? where token_hash=?", Timestamp.from(now), sha256(rawToken));
        return account;
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

    public record Account(String userId, String email, boolean passwordConfigured) {
    }
}

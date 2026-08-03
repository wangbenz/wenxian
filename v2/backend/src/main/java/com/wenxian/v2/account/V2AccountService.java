package com.wenxian.v2.account;

import com.wenxian.v2.api.V2ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class V2AccountService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Duration REGISTRATION_WINDOW = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final SharedAccountSessionService sessions;
    private final V2AccessPolicyService accessPolicy;
    private final PasswordEncoder passwords = new BCryptPasswordEncoder(10);
    private final SecureRandom random = new SecureRandom();
    private final String ipPepper;
    private final int sessionDays;

    public V2AccountService(JdbcTemplate jdbc, SharedAccountSessionService sessions,
                            V2AccessPolicyService accessPolicy,
                            @Value("${wenxian.v2.auth.ip-hash-pepper:change-this-in-production}") String ipPepper,
                            @Value("${wenxian.v2.auth.session-days:30}") int sessionDays) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.accessPolicy = accessPolicy;
        this.ipPepper = ipPepper;
        this.sessionDays = Math.max(1, Math.min(sessionDays, 365));
    }

    @Transactional
    public LoginOutcome register(String rawEmail, String rawPassword, String clientIp) {
        accessPolicy.assertRegistrationAllowed();
        String email = normalizeEmail(rawEmail);
        String password = validatePassword(rawPassword);
        if (user(email) != null) {
            throw V2ApiException.conflict("EMAIL_ALREADY_REGISTERED", "该邮箱已经注册，请切换到登录。",
                    "使用原邮箱和密码登录");
        }
        Instant now = Instant.now();
        String ipHash = sha256(ipPepper + ":" + normalizedIp(clientIp));
        Integer recent = jdbc.queryForObject("select count(*) from auth_registration where ip_hash=? and created_at>=?",
                Integer.class, ipHash, Timestamp.from(now.minus(REGISTRATION_WINDOW)));
        if (recent != null && recent > 0) {
            throw new V2ApiException(HttpStatus.TOO_MANY_REQUESTS, "REGISTRATION_NETWORK_LIMIT", "ACCOUNT",
                    "同一网络在二十四小时内只能注册一个账号。", List.of("使用已有账号登录", "稍后再注册"));
        }
        String userId = "user-" + UUID.randomUUID();
        jdbc.update("""
                insert into app_user(id,email,password_hash,password_set_at,registration_ip_hash,status,registered_at,last_login_at)
                values (?,?,?,?,?,'ACTIVE',?,?)
                """, userId, email, passwords.encode(password), Timestamp.from(now), ipHash,
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into auth_registration(id,user_id,ip_hash,created_at) values (?,?,?,?)",
                "registration-" + UUID.randomUUID(), userId, ipHash, Timestamp.from(now));
        return issue(new SharedAccountSessionService.Account(userId, email, true), true, now);
    }

    public LoginOutcome login(String rawEmail, String rawPassword) {
        String email = normalizeEmail(rawEmail);
        String password = rawPassword == null ? "" : rawPassword;
        UserRow user = user(email);
        boolean legacyInitialPassword = user != null && user.passwordHash() == null && password.equals(email);
        if (user == null || (!legacyInitialPassword &&
                (user.passwordHash() == null || !passwords.matches(password, user.passwordHash())))) {
            throw new V2ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_CREDENTIALS_INVALID", "ACCOUNT",
                    "邮箱或密码不正确。老用户首次登录可使用完整邮箱地址作为初始密码。",
                    List.of("检查邮箱和密码后重试"));
        }
        accessPolicy.assertAllowed(user.userId());
        Instant now = Instant.now();
        jdbc.update("update app_user set last_login_at=? where id=?", Timestamp.from(now), user.userId());
        return issue(new SharedAccountSessionService.Account(user.userId(), email, user.passwordHash() != null),
                false, now);
    }

    public SharedAccountSessionService.Account current(String token) {
        if (token == null || token.isBlank()) return null;
        return sessions.currentAccount(token);
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) return;
        jdbc.update("update user_session set revoked_at=? where token_hash=? and revoked_at is null",
                Timestamp.from(Instant.now()), sha256(token));
    }

    @Transactional
    public SharedAccountSessionService.Account changePassword(String token, String currentPassword,
                                                                String rawNewPassword) {
        SharedAccountSessionService.Account account = sessions.requireAccount(token);
        String next = validatePassword(rawNewPassword);
        UserRow user = user(account.email());
        if (user == null) throw V2ApiException.notFound("ACCOUNT_NOT_FOUND", "账号不存在。");
        if (user.passwordHash() != null && !passwords.matches(currentPassword == null ? "" : currentPassword,
                user.passwordHash())) {
            throw V2ApiException.badRequest("CURRENT_PASSWORD_INVALID", "当前密码不正确。", "重新输入当前密码");
        }
        jdbc.update("update app_user set password_hash=?,password_set_at=? where id=?",
                passwords.encode(next), Timestamp.from(Instant.now()), account.userId());
        return new SharedAccountSessionService.Account(account.userId(), account.email(), true);
    }

    private LoginOutcome issue(SharedAccountSessionService.Account account, boolean newUser, Instant now) {
        String token = token();
        jdbc.update("""
                insert into user_session(id,user_id,token_hash,expires_at,revoked_at,created_at)
                values (?,?,?,?,null,?)
                """, "session-" + UUID.randomUUID(), account.userId(), sha256(token),
                Timestamp.from(now.plus(Duration.ofDays(sessionDays))), Timestamp.from(now));
        return new LoginOutcome(account, newUser, token, sessionDays);
    }

    private UserRow user(String email) {
        return jdbc.query("select id,email,password_hash from app_user where email=? and status='ACTIVE'",
                (row, index) -> new UserRow(row.getString(1), row.getString(2), row.getString(3)), email)
                .stream().findFirst().orElse(null);
    }

    private String normalizeEmail(String raw) {
        String email = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 255 || !EMAIL.matcher(email).matches()) {
            throw V2ApiException.badRequest("EMAIL_INVALID", "请输入可以长期使用的完整邮箱地址。",
                    "检查邮箱格式");
        }
        return email;
    }

    private String validatePassword(String value) {
        String password = value == null ? "" : value;
        if (password.length() < 8 || password.length() > 72 || password.isBlank()) {
            throw V2ApiException.badRequest("PASSWORD_INVALID", "密码需要包含八至七十二个字符。",
                    "重新设置密码");
        }
        return password;
    }

    private String normalizedIp(String clientIp) {
        return clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.strip();
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record UserRow(String userId, String email, String passwordHash) {
    }

    public record LoginOutcome(SharedAccountSessionService.Account account, boolean newUser,
                               String rawToken, int sessionDays) {
    }
}

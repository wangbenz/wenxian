package com.wenxian.game.auth;

import jakarta.annotation.PostConstruct;
import com.wenxian.game.api.GameApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthService {
    public static final String COOKIE_NAME = "WENXIAN_SESSION";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Duration REGISTRATION_WINDOW = Duration.ofHours(24);
    private static final Duration LOGIN_FAILURE_WINDOW = Duration.ofMinutes(10);
    private static final int DEFAULT_REGISTRATION_MAX_USERS = 1000;
    private static final String DEFAULT_REGISTRATION_LIMIT_MESSAGE = "当前系统试玩人数较多，暂时停止新账号注册。";
    private static final String DEFAULT_REGISTRATION_CONTACT = "如需参与测试或反馈问题，请加入《问仙录》二群：532549329。";

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);
    private final SecureRandom secureRandom = new SecureRandom();
    private final String bootstrapInviteCode;
    private final int sessionDays;
    private final int idleHours;
    private final String ipHashPepper;
    private final int registrationsPerIpPerDay;
    private final int loginFailuresPerWindow;
    private final AccessPolicyService accessPolicy;

    @Autowired
    public AuthService(JdbcTemplate jdbcTemplate,
                       @Value("${game.invite.bootstrap-code:}") String bootstrapInviteCode,
                       @Value("${game.auth.session-days:30}") int sessionDays,
                       @Value("${game.auth.idle-hours:12}") int idleHours,
                       @Value("${game.auth.ip-hash-pepper:wenxian-local-ip-pepper-change-me}") String ipHashPepper,
                       @Value("${game.auth.registrations-per-ip-per-day:1}") int registrationsPerIpPerDay,
                       @Value("${game.auth.login-failures-per-window:5}") int loginFailuresPerWindow,
                       AccessPolicyService accessPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.bootstrapInviteCode = bootstrapInviteCode == null ? "" : bootstrapInviteCode.strip();
        this.sessionDays = Math.max(1, Math.min(sessionDays, 365));
        this.idleHours = Math.max(1, Math.min(idleHours, 168));
        this.ipHashPepper = ipHashPepper == null ? "" : ipHashPepper;
        this.registrationsPerIpPerDay = Math.max(1, Math.min(registrationsPerIpPerDay, 20));
        this.loginFailuresPerWindow = Math.max(3, Math.min(loginFailuresPerWindow, 30));
        this.accessPolicy = accessPolicy;
    }

    AuthService(JdbcTemplate jdbcTemplate, String bootstrapInviteCode, int sessionDays) {
        this(jdbcTemplate, bootstrapInviteCode, sessionDays, 12,
                "test-ip-pepper", 1, 5, new AccessPolicyService(jdbcTemplate));
    }

    @PostConstruct
    void ensureBootstrapInvite() {
        ensurePasswordColumns();
        ensureRegistrationConfig();
        if (bootstrapInviteCode.isBlank()) return;
        String hash = TokenHasher.sha256(bootstrapInviteCode);
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from invite_code where code_hash = ?", Integer.class, hash);
        if (count != null && count == 0) {
            jdbcTemplate.update("""
                    insert into invite_code(id, code_hash, status, max_uses, used_count, expires_at, created_at)
                    values (?, ?, 'ACTIVE', 20, 0, null, ?)
                    """, "invite-" + UUID.randomUUID(), hash, Timestamp.from(Instant.now()));
        }
    }

    @Transactional
    public LoginOutcome register(String rawEmail, String rawPassword, String clientIp) {
        accessPolicy.assertRegistrationAllowed();
        String email = normalizeEmail(rawEmail);
        String password = validatePassword(rawPassword);
        if (findUserByEmail(email) != null) {
            throw GameApiException.of(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "ACCOUNT",
                    "该邮箱已经登记", "该邮箱已经注册，请切换到登录继续原有命卷。",
                    java.util.List.of("切换到登录", "使用原邮箱和密码进入"));
        }
        ensureRegistrationConfig();
        String maxUsersValue = jdbcTemplate.queryForObject(
                "select config_value from gameplay_config where config_key='registration_max_users' for update",
                String.class);
        int maxUsers = positiveInt(maxUsersValue, DEFAULT_REGISTRATION_MAX_USERS);
        Integer registeredUsers = jdbcTemplate.queryForObject("select count(*) from app_user", Integer.class);
        if (registeredUsers != null && registeredUsers >= maxUsers) {
            String message = configValue("registration_limit_message", DEFAULT_REGISTRATION_LIMIT_MESSAGE);
            String contact = configValue("registration_contact_text", DEFAULT_REGISTRATION_CONTACT);
            throw GameApiException.of(HttpStatus.TOO_MANY_REQUESTS, "REGISTRATION_CAPACITY_REACHED", "ACCOUNT",
                    "当前暂停新账号登记", message + (contact.isBlank() ? "" : "\n" + contact),
                    java.util.List.of("已有玩家可直接登录", "按提示联系测试群"));
        }
        Instant now = Instant.now();
        String ipHash = ipHash(clientIp);
        Integer recent = jdbcTemplate.queryForObject(
                "select count(*) from auth_registration where ip_hash=? and created_at>=?",
                Integer.class, ipHash, Timestamp.from(now.minus(REGISTRATION_WINDOW)));
        if (recent != null && recent >= registrationsPerIpPerDay) {
            throw GameApiException.of(HttpStatus.TOO_MANY_REQUESTS, "REGISTRATION_NETWORK_LIMIT", "ACCOUNT",
                    "当前网络已登记过账号", "同一网络在24小时内只能登记一个账号，请使用已有账号登录。",
                    java.util.List.of("切换到登录", "24小时后再登记新账号"));
        }

        String userId = "user-" + UUID.randomUUID();
        jdbcTemplate.update("""
                insert into app_user(id, email, password_hash, password_set_at, registration_ip_hash,
                                     status, registered_at, last_login_at)
                values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, userId, email, passwordEncoder.encode(password), Timestamp.from(now), ipHash,
                Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("insert into auth_registration(id,user_id,ip_hash,created_at) values (?,?,?,?)",
                "registration-" + UUID.randomUUID(), userId, ipHash, Timestamp.from(now));
        return issueSession(new UserIdentity(userId, email, true), true, now);
    }

    public LoginOutcome login(String rawEmail, String rawPassword, String clientIp) {
        String email = normalizeEmail(rawEmail);
        String password = rawPassword == null ? "" : rawPassword;
        Instant now = Instant.now();
        String emailHash = TokenHasher.sha256(email);
        String ipHash = ipHash(clientIp);
        Integer recentFailures = jdbcTemplate.queryForObject("""
                select count(*) from auth_login_failure
                where email_hash=? and ip_hash=? and created_at>=?
                """, Integer.class, emailHash, ipHash, Timestamp.from(now.minus(LOGIN_FAILURE_WINDOW)));
        if (recentFailures != null && recentFailures >= loginFailuresPerWindow) {
            throw GameApiException.of(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED", "ACCOUNT",
                    "登录尝试过于频繁", "为了保护账号，本邮箱与当前网络需要等待十分钟后再试。",
                    java.util.List.of("十分钟后重新登录", "确认邮箱和密码后再试"));
        }

        UserIdentity user = findUserByEmail(email);
        String passwordHash = passwordHash(email);
        boolean legacyInitialPassword = user != null && passwordHash == null && password.equals(email);
        if (user == null || (!legacyInitialPassword
                && (passwordHash == null || !passwordEncoder.matches(password, passwordHash)))) {
            jdbcTemplate.update("insert into auth_login_failure(id,email_hash,ip_hash,created_at) values (?,?,?,?)",
                    "failure-" + UUID.randomUUID(), emailHash, ipHash, Timestamp.from(now));
            throw GameApiException.of(HttpStatus.UNAUTHORIZED, "LOGIN_CREDENTIALS_INVALID", "ACCOUNT",
                    "邮箱或密码不正确", "请检查邮箱和密码。老用户首次登录时，初始密码为完整邮箱地址。",
                    java.util.List.of("重新输入邮箱和密码", "老用户可尝试使用完整邮箱作为初始密码"));
        }

        accessPolicy.assertAllowed(user.userId());

        jdbcTemplate.update("delete from auth_login_failure where email_hash=? and ip_hash=?",
                emailHash, ipHash);
        jdbcTemplate.update("update app_user set last_login_at=? where id=?",
                Timestamp.from(now), user.userId());
        return issueSession(user, false, now);
    }

    @Transactional
    public UserIdentity setPassword(String rawToken, String currentPassword, String newPassword) {
        UserIdentity user = requireUser(rawToken);
        String next = validatePassword(newPassword);
        String existingHash = passwordHash(user.email());
        if (existingHash != null && !passwordEncoder.matches(currentPassword == null ? "" : currentPassword, existingHash)) {
            throw GameApiException.of(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INVALID", "ACCOUNT",
                    "当前密码不正确", "身份确认失败，新密码尚未保存。",
                    java.util.List.of("重新输入当前密码"));
        }
        Instant now = Instant.now();
        jdbcTemplate.update("update app_user set password_hash=?, password_set_at=? where id=?",
                passwordEncoder.encode(next), Timestamp.from(now), user.userId());
        return new UserIdentity(user.userId(), user.email(), true);
    }

    public UserIdentity currentUser(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;
        Instant now = Instant.now();
        String tokenHash = TokenHasher.sha256(rawToken);
        UserIdentity user = jdbcTemplate.query("""
                        select u.id, u.email, u.password_hash
                        from user_session s
                        join app_user u on u.id = s.user_id
                        where s.token_hash = ? and s.revoked_at is null and s.expires_at > ?
                          and s.created_at > ? and u.status = 'ACTIVE'
                        """,
                (result, row) -> new UserIdentity(result.getString("id"), result.getString("email"),
                        result.getString("password_hash") != null),
                tokenHash, Timestamp.from(now),
                Timestamp.from(now.minus(Duration.ofHours(idleHours))))
                .stream().findFirst().orElse(null);
        if (user != null) {
            accessPolicy.assertAllowed(user.userId());
            jdbcTemplate.update("update user_session set created_at=? where token_hash=?",
                    Timestamp.from(now), tokenHash);
            jdbcTemplate.update("update app_user set last_login_at=? where id=?",
                    Timestamp.from(now), user.userId());
        }
        return user;
    }

    public UserIdentity requireUser(String rawToken) {
        UserIdentity user = currentUser(rawToken);
        if (user == null) throw GameApiException.of(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "ACCOUNT",
                "登录状态已经失效", "请重新登录。刚才的操作没有修改账号或命卷。",
                java.util.List.of("重新登录"));
        return user;
    }

    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        jdbcTemplate.update("update user_session set revoked_at=? where token_hash=? and revoked_at is null",
                Timestamp.from(Instant.now()), TokenHasher.sha256(rawToken));
    }

    @Transactional
    public void deleteAccount(String rawToken) {
        UserIdentity user = requireUser(rawToken);
        String userId = user.userId();
        jdbcTemplate.update("""
                delete from agent_execution
                where save_id in (select id from game_save where user_id = ?)
                   or save_id in (select original_save_id from game_life_archive where user_id = ?)
                """, userId, userId);
        jdbcTemplate.update("delete from game_save where user_id=?", userId);
        jdbcTemplate.update("delete from game_life_archive where user_id=?", userId);
        jdbcTemplate.update("delete from user_release_receipt where user_id=?", userId);
        jdbcTemplate.update("delete from user_daily_quota where user_id=?", userId);
        jdbcTemplate.update("delete from user_ai_credential where user_id=?", userId);
        jdbcTemplate.update("delete from game_access_whitelist where user_id=?", userId);
        jdbcTemplate.update("delete from user_session where user_id=?", userId);
        jdbcTemplate.update("delete from auth_login_failure where email_hash=?",
                TokenHasher.sha256(user.email()));
        jdbcTemplate.update("delete from app_user where id=?", userId);
    }

    public int sessionDays() {
        return sessionDays;
    }

    private void ensureRegistrationConfig() {
        Timestamp now = Timestamp.from(Instant.now());
        putConfigIfAbsent("registration_max_users", String.valueOf(DEFAULT_REGISTRATION_MAX_USERS), now);
        putConfigIfAbsent("registration_limit_message", DEFAULT_REGISTRATION_LIMIT_MESSAGE, now);
        putConfigIfAbsent("registration_contact_text", DEFAULT_REGISTRATION_CONTACT, now);
    }

    private void putConfigIfAbsent(String key, String value, Timestamp now) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from gameplay_config where config_key=?", Integer.class, key);
        if (count != null && count > 0) return;
        try {
            jdbcTemplate.update("insert into gameplay_config(config_key,config_value,updated_at) values (?,?,?)",
                    key, value, now);
        } catch (DataAccessException error) {
            Integer existing = jdbcTemplate.queryForObject(
                    "select count(*) from gameplay_config where config_key=?", Integer.class, key);
            if (existing == null || existing == 0) throw error;
        }
    }

    private String configValue(String key, String fallback) {
        return jdbcTemplate.query("select config_value from gameplay_config where config_key=?",
                (row, index) -> row.getString(1), key).stream().findFirst().orElse(fallback);
    }

    private int positiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private LoginOutcome issueSession(UserIdentity user, boolean newUser, Instant now) {
        String rawToken = randomToken();
        Instant expiresAt = now.plus(Duration.ofDays(sessionDays));
        jdbcTemplate.update("""
                insert into user_session(id,user_id,token_hash,expires_at,revoked_at,created_at)
                values (?,?,?,?,null,?)
                """, "session-" + UUID.randomUUID(), user.userId(), TokenHasher.sha256(rawToken),
                Timestamp.from(expiresAt), Timestamp.from(now));
        return new LoginOutcome(user, newUser, rawToken, expiresAt);
    }

    private UserIdentity findUserByEmail(String email) {
        return jdbcTemplate.query("select id,email,password_hash from app_user where email=? and status='ACTIVE'",
                (result, row) -> new UserIdentity(result.getString("id"), result.getString("email"),
                        result.getString("password_hash") != null), email)
                .stream().findFirst().orElse(null);
    }

    private String passwordHash(String email) {
        return jdbcTemplate.query("select password_hash from app_user where email=? and status='ACTIVE'",
                result -> result.next() ? result.getString(1) : null, email);
    }

    private String normalizeEmail(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 255 || !EMAIL_PATTERN.matcher(email).matches()) {
            throw GameApiException.of(HttpStatus.BAD_REQUEST, "EMAIL_INVALID", "INPUT",
                    "邮箱格式不正确", "请输入可以长期接收邮件的完整邮箱地址。",
                    java.util.List.of("检查邮箱中是否包含 @ 和正确后缀"));
        }
        return email;
    }

    private String validatePassword(String rawPassword) {
        String password = rawPassword == null ? "" : rawPassword;
        if (password.length() < 8 || password.length() > 72 || password.isBlank()) {
            throw GameApiException.of(HttpStatus.BAD_REQUEST, "PASSWORD_INVALID", "INPUT",
                    "密码长度不符合要求", "密码需要包含8至72个字符。",
                    java.util.List.of("重新设置8至72个字符的密码"));
        }
        return password;
    }

    private String ipHash(String clientIp) {
        String normalized = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.strip();
        return TokenHasher.sha256(ipHashPepper + ":" + normalized);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void ensurePasswordColumns() {
        ensureColumn("password_hash", "varchar(100) null");
        ensureColumn("password_set_at", "timestamp(6) null");
        ensureColumn("registration_ip_hash", "varchar(128) null");
    }

    private void ensureColumn(String name, String definition) {
        try {
            jdbcTemplate.query("select " + name + " from app_user where 1=0", (row, index) -> row.getString(1));
        } catch (DataAccessException missing) {
            jdbcTemplate.execute("alter table app_user add column " + name + " " + definition);
        }
    }

    public record UserIdentity(String userId, String email, boolean passwordConfigured) {}
    public record LoginOutcome(UserIdentity user, boolean newUser, String rawToken, Instant expiresAt) {}
}

package com.wenxian.game.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String bootstrapInviteCode;
    private final int sessionDays;
    private final int idleHours;

    @Autowired
    public AuthService(JdbcTemplate jdbcTemplate,
                       @Value("${game.invite.bootstrap-code:WENXIAN-LOCAL}") String bootstrapInviteCode,
                       @Value("${game.auth.session-days:30}") int sessionDays,
                       @Value("${game.auth.idle-hours:12}") int idleHours) {
        this.jdbcTemplate = jdbcTemplate;
        this.bootstrapInviteCode = bootstrapInviteCode == null ? "" : bootstrapInviteCode.strip();
        this.sessionDays = Math.max(1, Math.min(sessionDays, 365));
        this.idleHours = Math.max(1, Math.min(idleHours, 168));
    }

    AuthService(JdbcTemplate jdbcTemplate, String bootstrapInviteCode, int sessionDays) {
        this(jdbcTemplate, bootstrapInviteCode, sessionDays, 12);
    }

    @PostConstruct
    void ensureBootstrapInvite() {
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
    public LoginOutcome login(String rawEmail, String rawInviteCode) {
        String email = normalizeEmail(rawEmail);
        UserIdentity user = findUserByEmail(email);
        boolean newUser = user == null;
        Instant now = Instant.now();

        if (newUser) {
            String inviteCode = rawInviteCode == null ? "" : rawInviteCode.strip();
            if (inviteCode.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "首次进入需要邀请码");
            }
            consumeInvite(inviteCode, now);
            String userId = "user-" + UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into app_user(id, email, status, registered_at, last_login_at)
                    values (?, ?, 'ACTIVE', ?, ?)
                    """, userId, email, Timestamp.from(now), Timestamp.from(now));
            user = new UserIdentity(userId, email);
        } else {
            jdbcTemplate.update("update app_user set last_login_at = ? where id = ?",
                    Timestamp.from(now), user.userId());
        }

        String rawToken = randomToken();
        Instant expiresAt = now.plus(Duration.ofDays(sessionDays));
        jdbcTemplate.update("""
                insert into user_session(id, user_id, token_hash, expires_at, revoked_at, created_at)
                values (?, ?, ?, ?, null, ?)
                """, "session-" + UUID.randomUUID(), user.userId(), TokenHasher.sha256(rawToken),
                Timestamp.from(expiresAt), Timestamp.from(now));
        return new LoginOutcome(user, newUser, rawToken, expiresAt);
    }

    public UserIdentity currentUser(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;
        Instant now = Instant.now();
        String tokenHash = TokenHasher.sha256(rawToken);
        UserIdentity user = jdbcTemplate.query("""
                        select u.id, u.email
                        from user_session s
                        join app_user u on u.id = s.user_id
                        where s.token_hash = ? and s.revoked_at is null and s.expires_at > ?
                          and s.created_at > ? and u.status = 'ACTIVE'
                        """,
                (result, row) -> new UserIdentity(result.getString("id"), result.getString("email")),
                tokenHash, Timestamp.from(now),
                Timestamp.from(now.minus(Duration.ofHours(idleHours))))
                .stream().findFirst().orElse(null);
        if (user != null) {
            jdbcTemplate.update("update user_session set created_at = ? where token_hash = ?",
                    Timestamp.from(now), tokenHash);
            jdbcTemplate.update("update app_user set last_login_at = ? where id = ?",
                    Timestamp.from(now), user.userId());
        }
        return user;
    }

    public UserIdentity requireUser(String rawToken) {
        UserIdentity user = currentUser(rawToken);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return user;
    }

    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        jdbcTemplate.update("update user_session set revoked_at = ? where token_hash = ? and revoked_at is null",
                Timestamp.from(Instant.now()), TokenHasher.sha256(rawToken));
    }

    public int sessionDays() {
        return sessionDays;
    }

    private void consumeInvite(String inviteCode, Instant now) {
        String hash = TokenHasher.sha256(inviteCode);
        Invite invite = jdbcTemplate.query("""
                        select id, status, max_uses, used_count, expires_at
                        from invite_code where code_hash = ?
                        """,
                (result, row) -> new Invite(
                        result.getString("id"), result.getString("status"), result.getInt("max_uses"),
                        result.getInt("used_count"), result.getTimestamp("expires_at") == null
                                ? null : result.getTimestamp("expires_at").toInstant()), hash)
                .stream().findFirst().orElse(null);
        if (invite == null || !"ACTIVE".equals(invite.status()) || invite.usedCount() >= invite.maxUses()
                || invite.expiresAt() != null && !invite.expiresAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邀请码无效或已失效");
        }
        int updated = jdbcTemplate.update("""
                update invite_code
                set used_count = used_count + 1,
                    status = case when used_count + 1 >= max_uses then 'EXHAUSTED' else status end
                where id = ? and status = 'ACTIVE' and used_count < max_uses
                """, invite.id());
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "邀请码已被使用，请更换邀请码");
    }

    private UserIdentity findUserByEmail(String email) {
        return jdbcTemplate.query("select id, email from app_user where email = ? and status = 'ACTIVE'",
                (result, row) -> new UserIdentity(result.getString("id"), result.getString("email")), email)
                .stream().findFirst().orElse(null);
    }

    private String normalizeEmail(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 255 || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入有效邮箱");
        }
        return email;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record UserIdentity(String userId, String email) {}
    public record LoginOutcome(UserIdentity user, boolean newUser, String rawToken, Instant expiresAt) {}
    private record Invite(String id, String status, int maxUses, int usedCount, Instant expiresAt) {}
}

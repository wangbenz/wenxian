package com.wenxian.game.auth;

import com.wenxian.game.api.GameApiException;
import com.wenxian.game.mail.MailDeliveryException;
import com.wenxian.game.mail.TransactionalMailGateway;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 注册邮箱验证码的申请、校验与一次性消费服务。
 *
 * <p>发信不占用数据库事务；发送前先持久化 PENDING_SEND，供应商确认后才切换 SENT。
 * 校验失败次数在独立事务中提交，校验成功形成 VERIFIED；最终 CONSUMED 必须加入账号创建事务。
 * 表内只保存邮箱、IP 和验证码的不可逆摘要，不保存邮件正文或验证码明文。</p>
 */
@Service
public class RegistrationEmailVerificationService implements RegistrationVerificationGate {
    private static final String PURPOSE = "REGISTER";
    private static final Duration RATE_WINDOW = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final AccessPolicyService accessPolicy;
    private final TransactionalMailGateway mailGateway;
    private final TransactionTemplate transaction;
    private final TransactionTemplate requiresNew;
    private final RegistrationEmailVerificationHasher hasher;
    private final SecureRandom random = new SecureRandom();
    private final boolean enabled;
    private final Duration expiresAfter;
    private final Duration resendAfter;
    private final int maxAttempts;
    private final int maxPerEmailPerHour;
    private final int maxPerIpPerHour;
    private final String ipHashPepper;

    @Autowired
    public RegistrationEmailVerificationService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            AccessPolicyService accessPolicy,
            ObjectProvider<TransactionalMailGateway> mailGatewayProvider,
            @Value("${game.auth.email-verification.enabled:false}") boolean enabled,
            @Value("${game.auth.email-verification.digest-pepper:}") String digestPepper,
            @Value("${game.auth.email-verification.expires-minutes:10}") int expiresMinutes,
            @Value("${game.auth.email-verification.resend-after-seconds:60}") int resendAfterSeconds,
            @Value("${game.auth.email-verification.max-attempts:5}") int maxAttempts,
            @Value("${game.auth.email-verification.max-per-email-per-hour:5}") int maxPerEmailPerHour,
            @Value("${game.auth.email-verification.max-per-ip-per-hour:10}") int maxPerIpPerHour,
            @Value("${game.auth.ip-hash-pepper:wenxian-local-ip-pepper-change-me}") String ipHashPepper) {
        this(jdbc, transactionManager, accessPolicy, mailGatewayProvider.getIfAvailable(), enabled, digestPepper,
                expiresMinutes, resendAfterSeconds, maxAttempts, maxPerEmailPerHour, maxPerIpPerHour, ipHashPepper);
    }

    RegistrationEmailVerificationService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            AccessPolicyService accessPolicy,
            TransactionalMailGateway mailGateway,
            boolean enabled,
            String digestPepper,
            int expiresMinutes,
            int resendAfterSeconds,
            int maxAttempts,
            int maxPerEmailPerHour,
            int maxPerIpPerHour,
            String ipHashPepper) {
        this.jdbc = jdbc;
        this.accessPolicy = accessPolicy;
        this.mailGateway = mailGateway;
        this.transaction = new TransactionTemplate(transactionManager);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.hasher = new RegistrationEmailVerificationHasher(digestPepper);
        this.enabled = enabled;
        this.expiresAfter = Duration.ofMinutes(expiresMinutes);
        this.resendAfter = Duration.ofSeconds(resendAfterSeconds);
        this.maxAttempts = maxAttempts;
        this.maxPerEmailPerHour = maxPerEmailPerHour;
        this.maxPerIpPerHour = maxPerIpPerHour;
        this.ipHashPepper = ipHashPepper == null ? "" : ipHashPepper;
    }

    @PostConstruct
    void validateConfiguration() {
        if (!enabled) return;
        hasher.assertReady();
        if (mailGateway == null) {
            throw new IllegalStateException(
                    "TransactionalMailGateway must be configured when registration email verification is enabled");
        }
        requireRange(expiresAfter.toMinutes(), 1, 30, "expires-minutes");
        requireRange(resendAfter.toSeconds(), 10, 300, "resend-after-seconds");
        requireRange(maxAttempts, 3, 10, "max-attempts");
        requireRange(maxPerEmailPerHour, 1, 20, "max-per-email-per-hour");
        requireRange(maxPerIpPerHour, 1, 100, "max-per-ip-per-hour");
        if (ipHashPepper.isBlank()) {
            throw new IllegalStateException("game.auth.ip-hash-pepper must be configured when email verification is enabled");
        }
    }

    public RequestOutcome requestRegistrationCode(String rawEmail, String clientIp) {
        requireEnabled();
        accessPolicy.assertRegistrationAllowed();
        String email = AuthEmailAddress.normalize(rawEmail);
        if (activeUserExists(email)) {
            throw GameApiException.of(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "ACCOUNT",
                    "该邮箱已经登记", "该邮箱已经注册，请切换到登录继续原有命卷。",
                    List.of("切换到登录", "使用原邮箱和密码进入"));
        }

        Instant now = Instant.now();
        String verificationId = "verification-" + UUID.randomUUID();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        String emailDigest = hasher.emailDigest(email);
        String ipDigest = ipDigest(clientIp);
        ReservedChallenge challenge = transaction.execute(status -> reserve(
                verificationId, email, emailDigest, ipDigest, code, now));
        if (challenge == null) throw new IllegalStateException("email verification reservation returned no result");

        try {
            mailGateway.send(new TransactionalMailGateway.Message(
                    email,
                    "问仙录注册验证码",
                    mailBody(code, expiresAfter.toMinutes())
            ));
        } catch (MailDeliveryException error) {
            transaction.executeWithoutResult(status -> markSendFailed(verificationId, Instant.now()));
            throw GameApiException.temporary("EMAIL_DELIVERY_UNAVAILABLE", "验证码暂时未能送达",
                    "邮件服务暂时没有确认发送成功，请稍后重新获取验证码。",
                    List.of("一分钟后重新获取验证码"), "direct mail delivery failed", error);
        }

        Instant sentAt = Instant.now();
        transaction.executeWithoutResult(status -> markSent(verificationId, emailDigest, sentAt));
        return new RequestOutcome(verificationId, expiresAfter.toSeconds(), resendAfter.toSeconds());
    }

    @Override
    public Confirmation confirm(String normalizedEmail, String verificationId, String code) {
        if (!enabled) return Confirmation.bypassed();
        if (verificationId == null || verificationId.isBlank() || code == null || code.isBlank()) {
            throw verificationRequired();
        }
        ConfirmResult result = requiresNew.execute(status -> confirmWithinTransaction(
                normalizedEmail, verificationId.strip(), code, Instant.now()));
        if (result == null) throw invalidVerification();
        if (result.error() != null) throw result.error();
        return result.confirmation();
    }

    @Override
    public void consume(Confirmation confirmation, String normalizedEmail, String userId, Instant consumedAt) {
        if (!enabled || confirmation == null || !confirmation.required()) return;
        String emailDigest = hasher.emailDigest(normalizedEmail);
        if (!emailDigest.equals(confirmation.emailDigest())) throw invalidVerification();
        int updated = jdbc.update("""
                update auth_email_verification
                set status='CONSUMED', user_id=?, consumed_at=?, updated_at=?
                where id=? and purpose=? and email_digest=? and status='VERIFIED' and expires_at>?
                """, userId, Timestamp.from(consumedAt), Timestamp.from(consumedAt),
                confirmation.verificationId(), PURPOSE, emailDigest, Timestamp.from(consumedAt));
        if (updated != 1) throw invalidVerification();
    }

    public boolean enabled() {
        return enabled;
    }

    private ReservedChallenge reserve(String verificationId, String email, String emailDigest, String ipDigest,
                                       String code, Instant now) {
        lockLatest(emailDigest, "email_digest");
        lockLatest(ipDigest, "request_ip_digest");
        Instant windowStart = now.minus(RATE_WINDOW);
        Timestamp latestEmail = latestCreatedAt(emailDigest, "email_digest");
        if (latestEmail != null && latestEmail.toInstant().plus(resendAfter).isAfter(now)) {
            throw rateLimited("验证码发送得太频繁", "请等待一分钟后再重新获取验证码。");
        }
        if (countSince(emailDigest, "email_digest", windowStart) >= maxPerEmailPerHour) {
            throw rateLimited("该邮箱获取验证码过于频繁", "请一小时后再重新获取验证码。");
        }
        if (countSince(ipDigest, "request_ip_digest", windowStart) >= maxPerIpPerHour) {
            throw rateLimited("当前网络获取验证码过于频繁", "请一小时后再重新获取验证码。");
        }

        Instant expiresAt = now.plus(expiresAfter);
        jdbc.update("""
                insert into auth_email_verification(
                    id,purpose,email_digest,request_ip_digest,code_digest,status,attempt_count,max_attempts,
                    expires_at,sent_at,consumed_at,failure_code,created_at,updated_at
                ) values (?,?,?,?,?,'PENDING_SEND',0,?,?,null,null,null,?,?)
                """, verificationId, PURPOSE, emailDigest, ipDigest,
                hasher.codeDigest(verificationId, email, code), maxAttempts, Timestamp.from(expiresAt),
                Timestamp.from(now), Timestamp.from(now));
        return new ReservedChallenge(verificationId, expiresAt);
    }

    private ConfirmResult confirmWithinTransaction(String email, String verificationId, String code, Instant now) {
        VerificationRow row = jdbc.query("""
                        select id,email_digest,code_digest,status,attempt_count,max_attempts,expires_at
                        from auth_email_verification where id=? and purpose=? for update
                        """,
                (result, index) -> new VerificationRow(
                        result.getString("id"), result.getString("email_digest"), result.getString("code_digest"),
                        result.getString("status"), result.getInt("attempt_count"), result.getInt("max_attempts"),
                        result.getTimestamp("expires_at").toInstant()),
                verificationId, PURPOSE).stream().findFirst().orElse(null);
        if (row == null || !hasher.emailDigest(email).equals(row.emailDigest())) {
            return ConfirmResult.failed(invalidVerification());
        }
        if (row.expiresAt().compareTo(now) <= 0) {
            jdbc.update("update auth_email_verification set status='EXPIRED',updated_at=? where id=?",
                    Timestamp.from(now), row.id());
            return ConfirmResult.failed(expiredVerification());
        }
        if ("LOCKED".equals(row.status()) || row.attemptCount() >= row.maxAttempts()) {
            return ConfirmResult.failed(lockedVerification());
        }
        if (!("SENT".equals(row.status()) || "VERIFIED".equals(row.status()))) {
            return ConfirmResult.failed(invalidVerification());
        }
        if (!hasher.codeMatches(row.codeDigest(), verificationId, email, code)) {
            int nextAttempts = row.attemptCount() + 1;
            String nextStatus = nextAttempts >= row.maxAttempts() ? "LOCKED" : row.status();
            jdbc.update("""
                    update auth_email_verification set attempt_count=?,status=?,updated_at=? where id=?
                    """, nextAttempts, nextStatus, Timestamp.from(now), row.id());
            return ConfirmResult.failed(nextAttempts >= row.maxAttempts()
                    ? lockedVerification() : invalidVerification());
        }
        if ("SENT".equals(row.status())) {
            jdbc.update("update auth_email_verification set status='VERIFIED',updated_at=? where id=?",
                    Timestamp.from(now), row.id());
        }
        return ConfirmResult.succeeded(new Confirmation(row.id(), row.emailDigest(), true));
    }

    private void markSent(String verificationId, String emailDigest, Instant sentAt) {
        jdbc.update("""
                update auth_email_verification
                set status='SUPERSEDED',updated_at=?
                where purpose=? and email_digest=? and id<>? and status in ('SENT','VERIFIED')
                """, Timestamp.from(sentAt), PURPOSE, emailDigest, verificationId);
        int updated = jdbc.update("""
                update auth_email_verification set status='SENT',sent_at=?,updated_at=?
                where id=? and status='PENDING_SEND'
                """, Timestamp.from(sentAt), Timestamp.from(sentAt), verificationId);
        if (updated != 1) throw new IllegalStateException("email verification send state transition failed");
    }

    private void markSendFailed(String verificationId, Instant failedAt) {
        jdbc.update("""
                update auth_email_verification
                set status='SEND_FAILED',failure_code=?,updated_at=?
                where id=? and status='PENDING_SEND'
                """, MailDeliveryException.ERROR_CODE, Timestamp.from(failedAt), verificationId);
    }

    private void lockLatest(String digest, String column) {
        jdbc.query("select id from auth_email_verification where " + column
                        + "=? order by created_at desc limit 1 for update",
                result -> null, digest);
    }

    private Timestamp latestCreatedAt(String digest, String column) {
        return jdbc.query("select created_at from auth_email_verification where " + column
                        + "=? order by created_at desc limit 1",
                result -> result.next() ? result.getTimestamp(1) : null, digest);
    }

    private int countSince(String digest, String column, Instant since) {
        Integer count = jdbc.queryForObject("select count(*) from auth_email_verification where " + column
                        + "=? and created_at>=?", Integer.class, digest, Timestamp.from(since));
        return count == null ? 0 : count;
    }

    private boolean activeUserExists(String email) {
        Integer count = jdbc.queryForObject(
                "select count(*) from app_user where email=? and status='ACTIVE'", Integer.class, email);
        return count != null && count > 0;
    }

    private String ipDigest(String clientIp) {
        String normalized = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.strip();
        return TokenHasher.sha256(ipHashPepper + ":" + normalized);
    }

    private String mailBody(String code, long expiresMinutes) {
        return """
                你正在注册《问仙录》账号。

                验证码：%s

                验证码将在 %d 分钟后失效，请勿转发给他人。
                如果不是你本人操作，可以忽略这封邮件。
                """.formatted(code, expiresMinutes).strip();
    }

    private void requireEnabled() {
        if (!enabled) {
            throw GameApiException.of(HttpStatus.CONFLICT, "EMAIL_VERIFICATION_NOT_ENABLED", "ACCOUNT",
                    "邮箱验证尚未开放", "当前版本暂未开放发送注册验证码。",
                    List.of("继续使用当前注册方式"));
        }
    }

    private void requireRange(long value, long minimum, long maximum, String property) {
        if (value < minimum || value > maximum) {
            throw new IllegalStateException("game.auth.email-verification." + property
                    + " must be between " + minimum + " and " + maximum);
        }
    }

    private GameApiException verificationRequired() {
        return GameApiException.of(HttpStatus.BAD_REQUEST, "EMAIL_VERIFICATION_REQUIRED", "ACCOUNT",
                "请先验证邮箱", "请先获取并填写邮箱验证码，再创建账号。",
                List.of("获取邮箱验证码", "填写邮件中的六位数字"));
    }

    private GameApiException invalidVerification() {
        return GameApiException.of(HttpStatus.BAD_REQUEST, "EMAIL_VERIFICATION_INVALID", "ACCOUNT",
                "验证码不正确", "验证码、邮箱或验证请求不匹配，请检查后重新输入。",
                List.of("重新输入六位验证码", "必要时重新获取验证码"));
    }

    private GameApiException expiredVerification() {
        return GameApiException.of(HttpStatus.CONFLICT, "EMAIL_VERIFICATION_EXPIRED", "ACCOUNT",
                "验证码已经失效", "验证码有效期已经结束，请重新获取。",
                List.of("重新获取验证码"));
    }

    private GameApiException lockedVerification() {
        return GameApiException.of(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_VERIFICATION_LOCKED", "ACCOUNT",
                "验证码尝试次数已用完", "为了保护邮箱，本次验证码已经锁定，请重新获取。",
                List.of("重新获取验证码"));
    }

    private GameApiException rateLimited(String title, String message) {
        return GameApiException.of(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_VERIFICATION_RATE_LIMITED", "ACCOUNT",
                title, message, List.of("稍后重新获取验证码"));
    }

    public record RequestOutcome(String verificationId, long expiresInSeconds, long resendAfterSeconds) { }

    private record ReservedChallenge(String id, Instant expiresAt) { }
    private record VerificationRow(String id, String emailDigest, String codeDigest, String status,
                                   int attemptCount, int maxAttempts, Instant expiresAt) { }
    private record ConfirmResult(Confirmation confirmation, GameApiException error) {
        static ConfirmResult succeeded(Confirmation confirmation) {
            return new ConfirmResult(confirmation, null);
        }

        static ConfirmResult failed(GameApiException error) {
            return new ConfirmResult(null, error);
        }
    }
}

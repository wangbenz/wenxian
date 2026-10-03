package com.wenxian.game.auth;

import com.wenxian.game.account.AccountActivityService;
import com.wenxian.game.account.AccountDeletionService;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.mail.MailDeliveryException;
import com.wenxian.game.mail.TransactionalMailGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationEmailVerificationServiceTest {
    private static final String DIGEST_PEPPER = "test-email-verification-pepper-32-bytes-minimum";
    private static final Pattern CODE = Pattern.compile("验证码：(\\d{6})");

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private AccessPolicyService accessPolicy;
    private CapturingMailGateway mail;
    private RegistrationEmailVerificationService verification;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).addScript("schema-h2.sql").build();
        jdbc = new JdbcTemplate(database);
        transactionManager = new DataSourceTransactionManager(database);
        accessPolicy = new AccessPolicyService(jdbc);
        mail = new CapturingMailGateway();
        verification = service(mail);
        verification.validateConfiguration();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void sendsCodeButPersistsOnlyKeyedDigestsThenConsumesWithRegistration() {
        RegistrationEmailVerificationService.RequestOutcome requested = verification.requestRegistrationCode(
                "Player@Example.com", "10.0.0.1");
        String code = mail.lastCode();

        assertThat(requested.expiresInSeconds()).isEqualTo(600);
        assertThat(requested.resendAfterSeconds()).isEqualTo(60);
        assertThat(mail.lastMessage.recipientEmail()).isEqualTo("player@example.com");
        assertThat(mail.lastMessage.subject()).isEqualTo("问仙录注册验证码");
        assertThat(jdbc.queryForObject("select status from auth_email_verification where id=?",
                String.class, requested.verificationId())).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select code_digest from auth_email_verification where id=?",
                String.class, requested.verificationId())).doesNotContain(code);
        assertThat(jdbc.queryForObject("select email_digest from auth_email_verification where id=?",
                String.class, requested.verificationId())).doesNotContain("player@example.com");

        AuthService auth = new AuthService(jdbc, "", 30, 12, "test-ip-pepper", 20, 5,
                accessPolicy, new AccountActivityService(jdbc), new AccountDeletionService(jdbc), verification);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        AuthService.LoginOutcome outcome = transaction.execute(status -> auth.register(
                "player@example.com", "password123", requested.verificationId(), code, "10.0.0.1"));

        assertThat(outcome).isNotNull();
        assertThat(outcome.newUser()).isTrue();
        assertThat(jdbc.queryForObject("select status from auth_email_verification where id=?",
                String.class, requested.verificationId())).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("select count(*) from app_user where email='player@example.com'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void failedRegistrationTransactionDoesNotConsumeVerifiedChallenge() {
        RegistrationEmailVerificationService.RequestOutcome requested = verification.requestRegistrationCode(
                "rollback@example.com", "10.0.0.2");
        RegistrationVerificationGate.Confirmation confirmation = verification.confirm(
                "rollback@example.com", requested.verificationId(), mail.lastCode());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            Instant now = Instant.now();
            jdbc.update("""
                    insert into app_user(id,email,status,registered_at,last_login_at)
                    values ('rollback-user','rollback@example.com','ACTIVE',?,?)
                    """, Timestamp.from(now), Timestamp.from(now));
            verification.consume(confirmation, "rollback@example.com", "rollback-user", now);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("select status from auth_email_verification where id=?",
                String.class, requested.verificationId())).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("select count(*) from app_user where id='rollback-user'", Integer.class))
                .isZero();
    }

    @Test
    void commitsFailedAttemptsAndLocksTheFifthWrongCode() {
        RegistrationEmailVerificationService.RequestOutcome requested = verification.requestRegistrationCode(
                "attempt@example.com", "10.0.0.3");
        String wrongCode = "000000".equals(mail.lastCode()) ? "111111" : "000000";

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThatThrownBy(() -> verification.confirm(
                    "attempt@example.com", requested.verificationId(), wrongCode))
                    .isInstanceOfSatisfying(GameApiException.class,
                            error -> assertThat(error.code()).isEqualTo("EMAIL_VERIFICATION_INVALID"));
        }
        assertThatThrownBy(() -> verification.confirm(
                "attempt@example.com", requested.verificationId(), wrongCode))
                .isInstanceOfSatisfying(GameApiException.class,
                        error -> assertThat(error.code()).isEqualTo("EMAIL_VERIFICATION_LOCKED"));

        assertThat(jdbc.queryForObject("select attempt_count from auth_email_verification where id=?",
                Integer.class, requested.verificationId())).isEqualTo(5);
        assertThat(jdbc.queryForObject("select status from auth_email_verification where id=?",
                String.class, requested.verificationId())).isEqualTo("LOCKED");
        assertThatThrownBy(() -> verification.confirm(
                "attempt@example.com", requested.verificationId(), mail.lastCode()))
                .isInstanceOfSatisfying(GameApiException.class,
                        error -> assertThat(error.code()).isEqualTo("EMAIL_VERIFICATION_LOCKED"));
    }

    @Test
    void rejectsExpiredAndMismatchedChallenges() {
        RegistrationEmailVerificationService.RequestOutcome requested = verification.requestRegistrationCode(
                "expiry@example.com", "10.0.0.4");

        assertThatThrownBy(() -> verification.confirm(
                "different@example.com", requested.verificationId(), mail.lastCode()))
                .isInstanceOfSatisfying(GameApiException.class,
                        error -> assertThat(error.code()).isEqualTo("EMAIL_VERIFICATION_INVALID"));

        jdbc.update("update auth_email_verification set expires_at=? where id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), requested.verificationId());
        assertThatThrownBy(() -> verification.confirm(
                "expiry@example.com", requested.verificationId(), mail.lastCode()))
                .isInstanceOfSatisfying(GameApiException.class,
                        error -> assertThat(error.code()).isEqualTo("EMAIL_VERIFICATION_EXPIRED"));
        assertThat(jdbc.queryForObject("select status from auth_email_verification where id=?",
                String.class, requested.verificationId())).isEqualTo("EXPIRED");
    }

    @Test
    void limitsImmediateResendAndRecordsProviderFailureWithoutSensitiveDetails() {
        verification.requestRegistrationCode("rate@example.com", "10.0.0.5");
        assertThatThrownBy(() -> verification.requestRegistrationCode("rate@example.com", "10.0.0.6"))
                .isInstanceOfSatisfying(GameApiException.class,
                        error -> assertThat(error.code()).isEqualTo("EMAIL_VERIFICATION_RATE_LIMITED"));

        CapturingMailGateway failing = new CapturingMailGateway();
        failing.fail = true;
        RegistrationEmailVerificationService failingService = service(failing);
        failingService.validateConfiguration();
        assertThatThrownBy(() -> failingService.requestRegistrationCode(
                "failure@example.com", "10.0.0.7"))
                .isInstanceOfSatisfying(GameApiException.class,
                        error -> assertThat(error.code()).isEqualTo("EMAIL_DELIVERY_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("""
                select status from auth_email_verification
                where email_digest=? order by created_at desc limit 1
                """, String.class, new RegistrationEmailVerificationHasher(DIGEST_PEPPER)
                .emailDigest("failure@example.com"))).isEqualTo("SEND_FAILED");
        assertThat(jdbc.queryForObject("""
                select failure_code from auth_email_verification
                where email_digest=? order by created_at desc limit 1
                """, String.class, new RegistrationEmailVerificationHasher(DIGEST_PEPPER)
                .emailDigest("failure@example.com"))).isEqualTo(MailDeliveryException.ERROR_CODE);
    }

    @Test
    void refusesEnabledConfigurationWithoutMailOrStrongPepper() {
        RegistrationEmailVerificationService missingMail = new RegistrationEmailVerificationService(
                jdbc, transactionManager, accessPolicy, (TransactionalMailGateway) null, true, DIGEST_PEPPER,
                10, 60, 5, 5, 10, "test-ip-pepper");
        assertThatThrownBy(missingMail::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TransactionalMailGateway");

        RegistrationEmailVerificationService weakPepper = new RegistrationEmailVerificationService(
                jdbc, transactionManager, accessPolicy, mail, true, "short",
                10, 60, 5, 5, 10, "test-ip-pepper");
        assertThatThrownBy(weakPepper::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }

    private RegistrationEmailVerificationService service(TransactionalMailGateway gateway) {
        return new RegistrationEmailVerificationService(
                jdbc, transactionManager, accessPolicy, gateway, true, DIGEST_PEPPER,
                10, 60, 5, 5, 10, "test-ip-pepper");
    }

    private static final class CapturingMailGateway implements TransactionalMailGateway {
        private Message lastMessage;
        private boolean fail;

        @Override
        public void send(Message message) {
            lastMessage = message;
            if (fail) throw new MailDeliveryException();
        }

        private String lastCode() {
            Matcher matcher = CODE.matcher(lastMessage.plainText());
            assertThat(matcher.find()).isTrue();
            return matcher.group(1);
        }
    }
}

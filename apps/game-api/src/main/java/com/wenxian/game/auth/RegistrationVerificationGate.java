package com.wenxian.game.auth;

import java.time.Instant;

/**
 * 注册事务使用的邮箱验证门禁。
 *
 * <p>确认阶段在独立事务中累计失败次数并形成短暂 VERIFIED 状态；消费阶段加入账号创建事务，
 * 账号写入失败时验证码消费一并回滚。</p>
 */
public interface RegistrationVerificationGate {
    Confirmation confirm(String normalizedEmail, String verificationId, String code);

    void consume(Confirmation confirmation, String normalizedEmail, String userId, Instant consumedAt);

    record Confirmation(String verificationId, String emailDigest, boolean required) {
        static Confirmation bypassed() {
            return new Confirmation("", "", false);
        }
    }

    static RegistrationVerificationGate bypassForTests() {
        return new RegistrationVerificationGate() {
            @Override
            public Confirmation confirm(String normalizedEmail, String verificationId, String code) {
                return Confirmation.bypassed();
            }

            @Override
            public void consume(Confirmation confirmation, String normalizedEmail, String userId,
                                Instant consumedAt) { }
        };
    }
}

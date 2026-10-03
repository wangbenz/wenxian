package com.wenxian.game.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 注册验证码敏感值摘要器。
 *
 * <p>六位验证码不能使用无密钥哈希，否则数据库泄露后可被离线穷举。这里使用服务端密钥 HMAC，
 * 并把验证码绑定到挑战 ID 和规范化邮箱。</p>
 */
final class RegistrationEmailVerificationHasher {
    private static final String ALGORITHM = "HmacSHA256";
    private final byte[] secret;

    RegistrationEmailVerificationHasher(String pepper) {
        this.secret = (pepper == null ? "" : pepper).getBytes(StandardCharsets.UTF_8);
    }

    void assertReady() {
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "game.auth.email-verification.digest-pepper must contain at least 32 UTF-8 bytes when enabled");
        }
    }

    String emailDigest(String normalizedEmail) {
        return hmac("email\u0000" + normalizedEmail);
    }

    String codeDigest(String verificationId, String normalizedEmail, String code) {
        return hmac("code\u0000" + verificationId + "\u0000" + normalizedEmail + "\u0000" + code);
    }

    boolean codeMatches(String expectedDigest, String verificationId, String normalizedEmail, String code) {
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(expectedDigest == null ? "" : expectedDigest);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        byte[] actual = HexFormat.of().parseHex(codeDigest(verificationId, normalizedEmail, code));
        return MessageDigest.isEqual(expected, actual);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("当前运行环境不支持 HmacSHA256", error);
        }
    }
}

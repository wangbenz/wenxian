package com.wenxian.game.model.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 只在后端内存中存在的模型凭据。
 *
 * <p>对象的字符串形式永不输出密钥。注册表使用不可逆指纹区分密钥轮换，
 * 不得把 {@link #secret()} 写入日志、缓存键、异常或玩家响应。</p>
 */
public final class ModelCredential {
    private final CredentialSource source;
    private final String secret;
    private final String fingerprint;

    private ModelCredential(CredentialSource source, String secret) {
        if (source == null) throw new IllegalArgumentException("密钥来源不能为空");
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("模型密钥不能为空");
        this.source = source;
        this.secret = secret.strip();
        this.fingerprint = sha256(this.secret);
    }

    public static ModelCredential platform(String secret) {
        return new ModelCredential(CredentialSource.PLATFORM, secret);
    }

    public static ModelCredential user(String secret) {
        return new ModelCredential(CredentialSource.USER, secret);
    }

    public CredentialSource source() {
        return source;
    }

    public String secret() {
        return secret;
    }

    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public String toString() {
        return "ModelCredential[source=" + source + ", fingerprint=" + fingerprint.substring(0, 12) + "]";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }
}

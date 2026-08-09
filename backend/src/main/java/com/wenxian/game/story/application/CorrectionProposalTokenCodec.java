package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.domain.StoryCorrectionProposal;
import com.wenxian.game.story.domain.StoryCorrectionValidator;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * 为两阶段修正签发和验证短时有效的认证加密令牌。
 *
 * <p>令牌绑定账号、命卷、命卷 revision、玩家原始修正说明、完整修正提案和失效时间。H5 只能原样回传，
 * 无法读取密钥或修改提案。该组件不写数据库；同一令牌首次提交后 revision 会递增，因此重复提交不能再次生效。</p>
 */
public final class CorrectionProposalTokenCodec {
    private static final String VERSION = "c1";
    private static final byte[] AAD = "wenxian-story-correction-c1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_LENGTH = 12;

    private final ObjectMapper mapper;
    private final SecretKeySpec key;
    private final Duration lifetime;
    private final Clock clock;
    private final SecureRandom random;
    private final StoryCorrectionValidator validator;

    public CorrectionProposalTokenCodec(ObjectMapper mapper, String secret, Duration lifetime, Clock clock) {
        this(mapper, secret, lifetime, clock, new SecureRandom(), new StoryCorrectionValidator());
    }

    CorrectionProposalTokenCodec(ObjectMapper mapper, String secret, Duration lifetime, Clock clock,
                                 SecureRandom random, StoryCorrectionValidator validator) {
        if (mapper == null) throw new IllegalArgumentException("修正令牌序列化器不能为空");
        if (secret == null || secret.length() < 16) throw new IllegalArgumentException("修正令牌密钥长度不足");
        if (lifetime == null || lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("修正令牌有效期必须大于0");
        }
        this.mapper = mapper;
        this.key = new SecretKeySpec(sha256(secret), "AES");
        this.lifetime = lifetime;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.random = random == null ? new SecureRandom() : random;
        this.validator = validator == null ? new StoryCorrectionValidator() : validator;
    }

    public String issue(String userId, String saveId, long expectedRevision, String instruction,
                        StoryCorrectionProposal proposal) {
        requireIdentity(userId, saveId, expectedRevision);
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("修正令牌缺少玩家原始说明");
        }
        validator.validate(proposal);
        Instant expiresAt = clock.instant().plus(lifetime);
        TokenPayload payload = new TokenPayload(VERSION, userId.strip(), saveId.strip(), expectedRevision,
                expiresAt.getEpochSecond(), instruction.strip(), proposal);
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv);
            byte[] encrypted = cipher.doFinal(mapper.writeValueAsBytes(payload));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return VERSION + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
        } catch (Exception error) {
            throw new IllegalStateException("修正提案令牌无法生成", error);
        }
    }

    /** 验证失败统一视为不可用提案，调用方不得透露密钥或内部解密细节。 */
    public VerifiedProposal verify(String token, String userId, String saveId, long expectedRevision) {
        requireIdentity(userId, saveId, expectedRevision);
        try {
            String prefix = VERSION + ".";
            if (token == null || !token.startsWith(prefix)) throw invalid(null);
            byte[] packed = Base64.getUrlDecoder().decode(token.substring(prefix.length()));
            if (packed.length <= IV_LENGTH) throw invalid(null);
            byte[] iv = Arrays.copyOfRange(packed, 0, IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(packed, IV_LENGTH, packed.length);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, iv);
            TokenPayload payload = mapper.readValue(cipher.doFinal(encrypted), TokenPayload.class);
            if (!VERSION.equals(payload.version())
                    || !userId.strip().equals(payload.userId())
                    || !saveId.strip().equals(payload.saveId())
                    || expectedRevision != payload.expectedRevision()
                    || payload.expiresAtEpochSecond() <= clock.instant().getEpochSecond()) {
                throw invalid(null);
            }
            if (payload.instruction() == null || payload.instruction().isBlank()) throw invalid(null);
            validator.validate(payload.proposal());
            return new VerifiedProposal(payload.instruction(), payload.proposal(),
                    Instant.ofEpochSecond(payload.expiresAtEpochSecond()));
        } catch (IllegalArgumentException error) {
            if ("修正提案无效或已经过期".equals(error.getMessage())) throw error;
            throw invalid(error);
        } catch (Exception error) {
            throw invalid(error);
        }
    }

    private Cipher cipher(int mode, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(AAD);
        return cipher;
    }

    private byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("修正令牌密钥无法初始化", error);
        }
    }

    private void requireIdentity(String userId, String saveId, long expectedRevision) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("修正令牌缺少账号");
        if (saveId == null || saveId.isBlank()) throw new IllegalArgumentException("修正令牌缺少命卷");
        if (expectedRevision < 1) throw new IllegalArgumentException("修正令牌缺少有效revision");
    }

    private InvalidProposalTokenException invalid(Throwable cause) {
        return new InvalidProposalTokenException("修正提案无效或已经过期", cause);
    }

    private record TokenPayload(
            String version,
            String userId,
            String saveId,
            long expectedRevision,
            long expiresAtEpochSecond,
            String instruction,
            StoryCorrectionProposal proposal
    ) {}

    public record VerifiedProposal(
            String instruction,
            StoryCorrectionProposal proposal,
            Instant expiresAt
    ) {}

    /** 认证失败、归属不符、revision 不符或过期都使用同一安全异常，避免泄露令牌内部细节。 */
    public static final class InvalidProposalTokenException extends IllegalArgumentException {
        private InvalidProposalTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.domain.StoryCorrectionProposal;
import com.wenxian.game.story.domain.StoryCorrectionProposal.CorrectionKind;
import com.wenxian.game.story.domain.StoryPatch;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrectionProposalTokenCodecTest {
    private static final String SECRET = "test-only-correction-token-secret";
    private static final Instant NOW = Instant.parse("2026-08-09T06:00:00Z");

    @Test
    void roundTripsProposalAndBindsAccountSaveRevisionAndExpiry() {
        CorrectionProposalTokenCodec codec = codec(NOW);
        StoryCorrectionProposal proposal = proposal();

        String token = codec.issue("user-1", "story-save-1", 12, "当前地点应为北门", proposal);
        CorrectionProposalTokenCodec.VerifiedProposal verified = codec.verify(
                token, "user-1", "story-save-1", 12);

        assertEquals("当前地点应为北门", verified.instruction());
        assertEquals(proposal, verified.proposal());
        assertEquals(NOW.plus(Duration.ofMinutes(10)), verified.expiresAt());
        assertTrue(token.startsWith("c1."));
    }

    @Test
    void rejectsTamperingAndWrongOwnershipOrRevision() {
        CorrectionProposalTokenCodec codec = codec(NOW);
        String token = codec.issue("user-1", "story-save-1", 12, "修正地点", proposal());
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        assertInvalid(() -> codec.verify(tampered, "user-1", "story-save-1", 12));
        assertInvalid(() -> codec.verify(token, "user-2", "story-save-1", 12));
        assertInvalid(() -> codec.verify(token, "user-1", "story-save-2", 12));
        assertInvalid(() -> codec.verify(token, "user-1", "story-save-1", 13));
    }

    @Test
    void rejectsExpiredProposalWithoutReinterpretingIt() {
        String token = codec(NOW).issue("user-1", "story-save-1", 12, "修正地点", proposal());
        CorrectionProposalTokenCodec expiredCodec = codec(NOW.plus(Duration.ofMinutes(11)));

        assertInvalid(() -> expiredCodec.verify(token, "user-1", "story-save-1", 12));
    }

    @Test
    void refusesWeakServerSecret() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CorrectionProposalTokenCodec(new ObjectMapper(), "short",
                        Duration.ofMinutes(10), Clock.systemUTC()));
        assertEquals("修正令牌密钥长度不足", error.getMessage());
    }

    private CorrectionProposalTokenCodec codec(Instant now) {
        return new CorrectionProposalTokenCodec(new ObjectMapper(), SECRET,
                Duration.ofMinutes(10), Clock.fixed(now, ZoneOffset.UTC));
    }

    private StoryCorrectionProposal proposal() {
        return new StoryCorrectionProposal(CorrectionKind.FACT, "当前地点记录有误。",
                "确认将当前地点修正为青岐城北门？",
                new StoryPatch.CoreStatePatch("青岐城北门", null, null, null),
                List.of(), List.of(), List.of());
    }

    private void assertInvalid(Runnable action) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals("修正提案无效或已经过期", error.getMessage());
    }
}

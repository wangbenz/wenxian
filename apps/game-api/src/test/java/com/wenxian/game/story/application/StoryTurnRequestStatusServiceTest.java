package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.credit.TurnCreditModels.Reservation;
import com.wenxian.game.credit.TurnCreditModels.ReservationStatus;
import com.wenxian.game.credit.TurnCreditRepository;
import com.wenxian.game.story.diagnostic.StoryTurnDiagnosticRepository;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredTurn;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证超时恢复只认不可变回合为成功事实，并正确区分处理中、失败和未知请求。 */
class StoryTurnRequestStatusServiceTest {
    private final StoryTurnRepository turns = mock(StoryTurnRepository.class);
    private final StoryTurnDiagnosticRepository diagnostics = mock(StoryTurnDiagnosticRepository.class);
    private final TurnCreditRepository credits = mock(TurnCreditRepository.class);
    private final StoryTurnRequestStatusService service = new StoryTurnRequestStatusService(turns, diagnostics, credits);

    @Test
    void committedTurnHasHighestAuthority() {
        var now = Instant.parse("2026-08-17T01:00:00Z");
        when(turns.findByRequestId("save-1", "request-1")).thenReturn(Optional.of(turn(now)));

        var result = service.query("user-1", "save-1", "request-1");

        assertEquals(StoryTurnRequestStatusService.Status.COMMITTED, result.status());
        assertTrue(result.terminal());
        assertFalse(result.retryable());
        assertEquals(now, result.updatedAt());
    }

    @Test
    void runningDiagnosticReportsProcessing() {
        when(turns.findByRequestId("save-1", "request-2")).thenReturn(Optional.empty());
        when(diagnostics.findRequestState("save-1", "request-2")).thenReturn(Optional.of(
                new StoryTurnDiagnosticRepository.RequestState("RUNNING", "MODEL_PROPOSAL", "", Instant.now())));

        var result = service.query("user-1", "save-1", "request-2");

        assertEquals(StoryTurnRequestStatusService.Status.PROCESSING, result.status());
        assertFalse(result.terminal());
        assertEquals(2500, result.retryAfterMs());
    }

    @Test
    void failedDiagnosticAllowsARealRetry() {
        when(turns.findByRequestId("save-1", "request-3")).thenReturn(Optional.empty());
        when(diagnostics.findRequestState("save-1", "request-3")).thenReturn(Optional.of(
                new StoryTurnDiagnosticRepository.RequestState("FAILED", "MODEL_REVIEW", "PROVIDER_TIMEOUT", Instant.now())));

        var result = service.query("user-1", "save-1", "request-3");

        assertEquals(StoryTurnRequestStatusService.Status.FAILED, result.status());
        assertTrue(result.terminal());
        assertTrue(result.retryable());
    }

    @Test
    void reservationCanRecoverBeforeDiagnosticRowAppears() {
        when(turns.findByRequestId("save-1", "request-4")).thenReturn(Optional.empty());
        when(diagnostics.findRequestState("save-1", "request-4")).thenReturn(Optional.empty());
        when(credits.findReservation("user-1", "save-1", "request-4")).thenReturn(Optional.of(
                new Reservation("reservation-1", "user-1", "save-1", "request-4", "grant-1", 1,
                        ReservationStatus.RESERVED, Instant.now().plusSeconds(300), null, Instant.now(), Instant.now())));

        var result = service.query("user-1", "save-1", "request-4");

        assertEquals(StoryTurnRequestStatusService.Status.PROCESSING, result.status());
        assertFalse(result.terminal());
    }

    @Test
    void missingEvidenceRemainsUnknownInsteadOfClaimingFailure() {
        when(turns.findByRequestId("save-1", "request-5")).thenReturn(Optional.empty());
        when(diagnostics.findRequestState("save-1", "request-5")).thenReturn(Optional.empty());
        when(credits.findReservation("user-1", "save-1", "request-5")).thenReturn(Optional.empty());

        var result = service.query("user-1", "save-1", "request-5");

        assertEquals(StoryTurnRequestStatusService.Status.UNKNOWN, result.status());
        assertFalse(result.terminal());
        assertTrue(result.retryable());
    }

    private StoredTurn turn(Instant createdAt) {
        var mapper = new ObjectMapper();
        return new StoredTurn("turn-1", "save-1", "request-1", 1, "观察石门", "你停在门前。",
                mapper.createArrayNode(), mapper.createObjectNode(), 0, 10, "LIGHT_NARRATIVE", 1,
                2, "prompt-v1", "DEEPSEEK", "flash", "PLATFORM", 100, 50, 0, 100,
                500, "V0.16", createdAt);
    }
}

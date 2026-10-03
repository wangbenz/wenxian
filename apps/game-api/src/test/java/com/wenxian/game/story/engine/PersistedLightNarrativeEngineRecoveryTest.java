package com.wenxian.game.story.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.credit.PlatformTurnAccessService;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.story.agent.ai.StoryCandidateGenerator.Session;
import com.wenxian.game.story.application.PreparedStoryTurn;
import com.wenxian.game.story.application.StorySaveViewService;
import com.wenxian.game.story.application.StoryTurnCommitService;
import com.wenxian.game.story.application.TrustedStoryTurnPreparationService;
import com.wenxian.game.story.domain.StoryRuntimeState;
import com.wenxian.game.story.model.StoryApiModels.SaveResponse;
import com.wenxian.game.story.model.StoryApiModels.TurnRequest;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredTurn;
import com.wenxian.game.story.persistence.StorySaveRepository;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 H5 重发原请求时的幂等、旧版本拒绝和单实例并发保护；不访问数据库或模型。 */
class PersistedLightNarrativeEngineRecoveryTest {
    private static final String SAVE_ID = "story-recovery";
    private static final String REQUEST_ID = "request-recovery";
    private final UserIdentity user = new UserIdentity("user-recovery", "recovery@example.test", true);
    private final StorySaveRepository saves = mock(StorySaveRepository.class);
    private final StoryTurnRepository turns = mock(StoryTurnRepository.class);
    private final TrustedStoryTurnPreparationService preparation = mock(TrustedStoryTurnPreparationService.class);
    private final StoryTurnCommitService commits = mock(StoryTurnCommitService.class);
    private final PlatformTurnAccessService access = mock(PlatformTurnAccessService.class);
    private final StorySaveViewService views = mock(StorySaveViewService.class);
    private final PersistedLightNarrativeEngine engine = new PersistedLightNarrativeEngine(
            saves, turns, null, null, preparation, commits, null, null, null, null,
            access, null, null, new ObjectMapper(), views, null);

    @Test
    void committedOriginalRequestReturnsCurrentSnapshotWithoutGeneratingOrReservingAgain() {
        StoredSave saved = saved(8);
        SaveResponse snapshot = snapshot(saved);
        when(turns.findByRequestId(SAVE_ID, REQUEST_ID)).thenReturn(Optional.of(mock(StoredTurn.class)));

        SaveResponse result = engine.takeTurn(user, SAVE_ID, request(7));

        assertSame(snapshot, result);
        verify(turns).findByRequestId(SAVE_ID, REQUEST_ID);
        verifyNoInteractions(preparation, access, commits);
    }

    @Test
    void uncommittedOriginalRequestWithStaleRevisionIsRejectedBeforeGeneratingOrReserving() {
        when(saves.findOwned(user.userId(), SAVE_ID)).thenReturn(Optional.of(saved(8)));
        when(turns.findByRequestId(SAVE_ID, REQUEST_ID)).thenReturn(Optional.empty());

        GameApiException failure = assertThrows(GameApiException.class,
                () -> engine.takeTurn(user, SAVE_ID, request(7)));

        assertEquals(HttpStatus.CONFLICT, failure.status());
        assertEquals("STATE_CONFLICT", failure.code());
        verifyNoInteractions(preparation, access, commits, views);
    }

    @Test
    void concurrentOriginalRequestIsRejectedWithoutPreparingASecondTurn() throws Exception {
        StoredSave saved = saved(7);
        SaveResponse snapshot = snapshot(saved);
        TurnRequest request = request(7);
        Session session = mock(Session.class);
        PreparedStoryTurn prepared = mock(PreparedStoryTurn.class);
        var permit = new PlatformTurnAccessService.Permit(PlatformTurnAccessService.Mode.PERSONAL, null);
        when(turns.findByRequestId(SAVE_ID, REQUEST_ID)).thenReturn(Optional.empty());
        when(preparation.openSession(saved, REQUEST_ID)).thenReturn(session);
        when(session.credentialSource()).thenReturn(CredentialSource.USER);
        when(session.servicePlanSelection()).thenReturn(Optional.empty());
        when(access.reserve(user.userId(), SAVE_ID, REQUEST_ID, CredentialSource.USER, null)).thenReturn(permit);
        CountDownLatch preparationEntered = new CountDownLatch(1);
        CountDownLatch finishPreparation = new CountDownLatch(1);
        when(preparation.prepare(saved, REQUEST_ID, request.action(), request.choiceId(), session))
                .thenAnswer(invocation -> {
                    preparationEntered.countDown();
                    assertTrue(finishPreparation.await(10, TimeUnit.SECONDS), "测试必须释放首个回合");
                    return prepared;
                });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> engine.takeTurn(user, SAVE_ID, request));
            try {
                assertTrue(preparationEntered.await(5, TimeUnit.SECONDS), "首个回合应进入准备阶段");
                var repeated = executor.submit(() -> engine.takeTurn(user, SAVE_ID, request));
                ExecutionException rejected = assertThrows(ExecutionException.class,
                        () -> repeated.get(5, TimeUnit.SECONDS));
                GameApiException failure = assertInstanceOf(GameApiException.class, rejected.getCause());
                assertEquals(HttpStatus.CONFLICT, failure.status());
                assertEquals("TURN_IN_PROGRESS", failure.code());
                verifyNoInteractions(commits);
            } finally {
                finishPreparation.countDown();
            }
            assertSame(snapshot, first.get(5, TimeUnit.SECONDS));
        }

        verify(preparation, times(1)).openSession(saved, REQUEST_ID);
        verify(preparation, times(1)).prepare(saved, REQUEST_ID, request.action(), request.choiceId(), session);
        verify(access, times(1)).reserve(user.userId(), SAVE_ID, REQUEST_ID, CredentialSource.USER, null);
        verify(commits, times(1)).commit(user.userId(), saved, REQUEST_ID, request.action(), prepared, permit);
    }

    private SaveResponse snapshot(StoredSave saved) {
        StoryRuntimeState state = mock(StoryRuntimeState.class);
        SaveResponse response = mock(SaveResponse.class);
        when(saves.findOwned(user.userId(), SAVE_ID)).thenReturn(Optional.of(saved));
        when(views.read(saved)).thenReturn(state);
        when(views.response(user.userId(), saved, state, "LIGHT_NARRATIVE")).thenReturn(response);
        return response;
    }

    private StoredSave saved(long revision) {
        Instant now = Instant.parse("2026-09-06T00:00:00Z");
        return new StoredSave(SAVE_ID, user.userId(), 1, 1, "ACTIVE", "XUANXU", "LIGHT_NARRATIVE", 1,
                2, "NATIVE", null, null, null, new ObjectMapper().createObjectNode(), revision, 3,
                "TEST", "TEST", now, now, null, null, null);
    }

    private TurnRequest request(long revision) {
        return new TurnRequest("整理已经确认的线索后停下", REQUEST_ID, revision, "choice-recovery");
    }
}

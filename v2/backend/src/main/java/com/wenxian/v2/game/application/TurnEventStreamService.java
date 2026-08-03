package com.wenxian.v2.game.application;

import com.wenxian.v2.api.V2ApiException;
import com.wenxian.v2.game.persistence.TurnRepository;
import com.wenxian.v2.game.persistence.TurnRepository.Operation;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class TurnEventStreamService {
    private static final long STREAM_TIMEOUT_MILLIS = 180_000L;
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCEEDED", "REJECTED", "FAILED");

    private final TurnRepository turns;

    public TurnEventStreamService(TurnRepository turns) {
        this.turns = turns;
    }

    public SseEmitter stream(String userId, String operationId) {
        Operation initial = turns.findOwned(operationId, userId);
        if (initial == null) {
            throw V2ApiException.notFound("TURN_OPERATION_NOT_FOUND", "没有找到这次推演记录。");
        }

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(ignored -> closed.set(true));

        Thread.ofVirtual().name("v2-turn-events-" + operationId).start(() -> publish(emitter, closed,
                userId, operationId, initial));
        return emitter;
    }

    private void publish(SseEmitter emitter, AtomicBoolean closed, String userId, String operationId,
                         Operation initial) {
        Operation current = initial;
        String lastFingerprint = "";
        try {
            while (!closed.get()) {
                if (current == null) {
                    emitter.complete();
                    return;
                }
                String fingerprint = current.status() + ":" + current.stage() + ":" + current.resultTurnId()
                        + ":" + current.errorCode();
                if (!fingerprint.equals(lastFingerprint)) {
                    emitter.send(SseEmitter.event().name("operation").data(current));
                    lastFingerprint = fingerprint;
                }
                if (TERMINAL_STATUSES.contains(current.status())) {
                    emitter.complete();
                    return;
                }
                Thread.sleep(400);
                current = turns.findOwned(operationId, userId);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (IOException | RuntimeException disconnected) {
            emitter.completeWithError(disconnected);
        }
    }
}

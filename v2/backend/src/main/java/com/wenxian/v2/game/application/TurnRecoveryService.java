package com.wenxian.v2.game.application;

import com.wenxian.v2.game.persistence.TurnRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class TurnRecoveryService {
    private final TurnRepository turns;
    private final TurnWorker worker;

    public TurnRecoveryService(TurnRepository turns, TurnWorker worker) {
        this.turns = turns;
        this.worker = worker;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        turns.failInterruptedOperations(Instant.now());
        turns.queuedOperationIds().forEach(worker::start);
    }
}

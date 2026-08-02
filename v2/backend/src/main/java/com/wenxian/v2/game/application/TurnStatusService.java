package com.wenxian.v2.game.application;

import com.wenxian.v2.game.persistence.TurnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class TurnStatusService {
    private final TurnRepository turns;

    public TurnStatusService(TurnRepository turns) {
        this.turns = turns;
    }

    @Transactional
    public void reject(String operationId, String code, String message) {
        turns.reject(operationId, code, message, Instant.now());
    }

    @Transactional
    public void fail(String operationId, String reference) {
        turns.fail(operationId, reference, Instant.now());
    }
}

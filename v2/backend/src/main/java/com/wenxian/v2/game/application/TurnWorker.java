package com.wenxian.v2.game.application;

import com.wenxian.v2.api.V2ApiException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TurnWorker {
    private final TurnResolutionService resolution;
    private final TurnStatusService statuses;

    public TurnWorker(TurnResolutionService resolution, TurnStatusService statuses) {
        this.resolution = resolution;
        this.statuses = statuses;
    }

    @Async("v2TurnExecutor")
    public void start(String operationId) {
        try {
            resolution.resolve(operationId);
        } catch (V2ApiException business) {
            statuses.reject(operationId, business.code(), business.getMessage());
        } catch (RuntimeException unexpected) {
            statuses.fail(operationId, UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
    }
}

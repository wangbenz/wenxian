package com.wenxian.v2.game.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.v2.api.V2ApiException;
import com.wenxian.v2.game.domain.DeterministicAction;
import com.wenxian.v2.game.domain.GameRecords.Save;
import com.wenxian.v2.game.persistence.GameSaveRepository;
import com.wenxian.v2.game.persistence.TurnRepository;
import com.wenxian.v2.game.persistence.TurnRepository.Operation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;

@Service
public class TurnApplicationService {
    private final GameSaveRepository saves;
    private final TurnRepository turns;
    private final TurnWorker worker;
    private final ObjectMapper objectMapper;

    public TurnApplicationService(GameSaveRepository saves, TurnRepository turns,
                                  TurnWorker worker, ObjectMapper objectMapper) {
        this.saves = saves;
        this.turns = turns;
        this.worker = worker;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Operation submit(String userId, String saveId, SubmitActionCommand command) {
        validate(command);
        Operation existing = turns.findByRequest(saveId, command.requestId());
        if (existing != null) return existing;

        saves.lockUser(userId);
        Save save = saves.lockOwned(saveId, userId);
        if (save == null) throw V2ApiException.notFound("SAVE_NOT_FOUND", "没有找到这份命卷。");
        if (!"ACTIVE".equals(save.status())) {
            throw V2ApiException.conflict("SAVE_NOT_ACTIVE", "这份命卷已经不能继续推进。", "查看只读终卷");
        }
        if (save.stateVersion() != command.expectedStateVersion()) {
            throw V2ApiException.conflict("STATE_VERSION_CONFLICT",
                    "命卷已经在其他页面发生变化。", "刷新当前命卷后重新提交");
        }
        if (turns.activeForUser(userId) > 0) {
            throw V2ApiException.conflict("TURN_ALREADY_RUNNING",
                    "当前账号已有一轮命卷正在推演。", "等待当前推演完成");
        }

        DeterministicAction action = DeterministicAction.parse(command.actionType());
        String operationId = "v2-operation-" + UUID.randomUUID();
        Instant now = Instant.now();
        Operation operation = new Operation(operationId, command.requestId(), saveId, userId,
                command.expectedStateVersion(), "QUEUED", "QUEUED", action.name(),
                command.actionText().strip(), payload(command), null, null, null, null, now, null, null);
        turns.insert(operation);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                worker.start(operationId);
            }
        });
        return operation;
    }

    public Operation requireOperation(String userId, String operationId) {
        Operation operation = turns.findOwned(operationId, userId);
        if (operation == null) throw V2ApiException.notFound("TURN_OPERATION_NOT_FOUND", "没有找到本次推演任务。");
        return operation;
    }

    private void validate(SubmitActionCommand command) {
        if (command == null) throw V2ApiException.badRequest("ACTION_REQUIRED", "缺少行动信息。");
        if (command.requestId() == null || command.requestId().isBlank() || command.requestId().length() > 80) {
            throw V2ApiException.badRequest("REQUEST_ID_INVALID", "行动缺少有效的请求编号。");
        }
        if (command.actionText() == null || command.actionText().isBlank() || command.actionText().length() > 1000) {
            throw V2ApiException.badRequest("ACTION_TEXT_INVALID", "行动内容需要1至1000个字符。");
        }
        DeterministicAction action = DeterministicAction.parse(command.actionType());
        if (action == DeterministicAction.WAIT
                && (command.durationMinutes() == null || command.durationMinutes() <= 0)) {
            throw V2ApiException.badRequest("WAIT_DURATION_REQUIRED", "等待行动需要明确经过时间。");
        }
        if (action == DeterministicAction.TRAVEL
                && (command.targetLocationId() == null || command.targetLocationId().isBlank())) {
            throw V2ApiException.badRequest("TRAVEL_TARGET_REQUIRED", "移动行动需要明确目的地。");
        }
    }

    private String payload(SubmitActionCommand command) {
        try {
            return objectMapper.writeValueAsString(new ActionPayload(command.durationMinutes(), command.targetLocationId()));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Unable to encode action", impossible);
        }
    }

    public record SubmitActionCommand(
            String requestId,
            long expectedStateVersion,
            String actionType,
            String actionText,
            Long durationMinutes,
            String targetLocationId
    ) {
    }

    public record ActionPayload(Long durationMinutes, String targetLocationId) {
    }
}

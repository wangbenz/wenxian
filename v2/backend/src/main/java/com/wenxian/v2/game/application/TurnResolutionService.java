package com.wenxian.v2.game.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.v2.api.V2ApiException;
import com.wenxian.v2.game.application.TurnApplicationService.ActionPayload;
import com.wenxian.v2.game.domain.DeterministicAction;
import com.wenxian.v2.game.domain.GameRecords.Save;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import com.wenxian.v2.game.domain.WorldCalendar;
import com.wenxian.v2.game.persistence.GameSaveRepository;
import com.wenxian.v2.game.persistence.LocationRepository;
import com.wenxian.v2.game.persistence.LocationRepository.TravelEdge;
import com.wenxian.v2.game.persistence.TurnRepository;
import com.wenxian.v2.game.persistence.TurnRepository.DomainChange;
import com.wenxian.v2.game.persistence.TurnRepository.Operation;
import com.wenxian.v2.game.persistence.TurnRepository.Turn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class TurnResolutionService {
    private static final long MAX_WAIT_MINUTES = 100L * WorldCalendar.MINUTES_PER_YEAR;

    private final GameSaveRepository saves;
    private final LocationRepository locations;
    private final TurnRepository turns;
    private final ObjectMapper objectMapper;

    public TurnResolutionService(GameSaveRepository saves, LocationRepository locations,
                                 TurnRepository turns, ObjectMapper objectMapper) {
        this.saves = saves;
        this.locations = locations;
        this.turns = turns;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void resolve(String operationId) {
        Operation operation = turns.lock(operationId);
        if (operation == null || !"QUEUED".equals(operation.status())) return;
        Instant now = Instant.now();
        turns.updateStage(operationId, "RESOLVING", "RESOLVING", now);

        Save save = saves.lockOwned(operation.saveId(), operation.userId());
        if (save == null) throw V2ApiException.notFound("SAVE_NOT_FOUND", "没有找到这份命卷。");
        if (save.stateVersion() != operation.expectedStateVersion()) {
            throw V2ApiException.conflict("STATE_VERSION_CONFLICT",
                    "命卷已经在其他页面发生变化。", "刷新当前命卷后重新提交");
        }

        SaveAggregate aggregate = saves.aggregate(save.id(), save.userId());
        DeterministicAction action = DeterministicAction.valueOf(operation.actionType());
        ActionPayload payload = decode(operation.actionPayload());
        Resolution resolved = switch (action) {
            case WAIT -> waitResolution(aggregate, payload);
            case TRAVEL -> travelResolution(aggregate, payload);
        };

        turns.updateStage(operationId, "COMMITTING", "COMMITTING", now);
        long beforeMinute = aggregate.worldMinute();
        long afterMinute = Math.addExact(beforeMinute, resolved.elapsedMinutes());
        long beforeVersion = save.stateVersion();
        long afterVersion = beforeVersion + 1;
        int turnNumber = save.turnNumber() + 1;
        String turnId = "v2-turn-" + UUID.randomUUID();

        saves.updateWorldMinute(save.id(), afterMinute, now);
        if (resolved.targetLocationId() != null) {
            saves.updateCharacterLocation(aggregate.protagonist().id(), resolved.targetLocationId(), afterVersion, now);
            saves.revealStartingLocations(save.id(), resolved.targetLocationId(), afterMinute, now);
        }
        saves.advanceSave(save.id(), turnNumber, afterVersion, now);

        Turn turn = new Turn(turnId, operationId, save.id(), turnNumber, operation.actionText(),
                resolved.intentJson(), "SUCCEEDED", resolved.summary(), resolved.narrative(), "[]",
                beforeMinute, afterMinute, beforeVersion, afterVersion, now);
        turns.insertTurn(turn);
        turns.insertChange(new DomainChange("v2-change-" + UUID.randomUUID(), turnId, save.id(), 1,
                "TIME", save.id(), "WORLD_TIME_ADVANCED", String.valueOf(beforeMinute),
                String.valueOf(afterMinute), resolved.timeReason(), now));
        if (resolved.targetLocationId() != null) {
            turns.insertChange(new DomainChange("v2-change-" + UUID.randomUUID(), turnId, save.id(), 2,
                    "CHARACTER", aggregate.protagonist().id(), "LOCATION_CHANGED",
                    aggregate.characterState().locationId(), resolved.targetLocationId(),
                    "沿已存在路线完成移动", now));
        }
        turns.succeed(operationId, turnId, now);
    }

    private Resolution waitResolution(SaveAggregate aggregate, ActionPayload payload) {
        long minutes = payload.durationMinutes() == null ? 0 : payload.durationMinutes();
        if (minutes <= 0 || minutes > MAX_WAIT_MINUTES) {
            throw V2ApiException.badRequest("WAIT_DURATION_INVALID", "等待时间需要在一分钟至一百年之间。");
        }
        WorldCalendar.WorldDate target = WorldCalendar.fromWorldMinute(aggregate.worldMinute() + minutes);
        String summary = "在%s度过了%d分钟".formatted(aggregate.characterState().locationName(), minutes);
        String narrative = "你留在%s，让时间安静地向前流过。再抬眼时，命卷已经来到%s。"
                .formatted(aggregate.characterState().locationName(), target.display());
        return new Resolution(minutes, null, "{\"type\":\"WAIT\"}", summary, narrative, "玩家明确选择等待");
    }

    private Resolution travelResolution(SaveAggregate aggregate, ActionPayload payload) {
        if (payload.targetLocationId() == null || !locations.exists(payload.targetLocationId())) {
            throw V2ApiException.badRequest("TRAVEL_TARGET_UNKNOWN", "目的地不属于当前已开放世界。");
        }
        TravelEdge edge = locations.directEdge(aggregate.characterState().locationId(), payload.targetLocationId());
        if (edge == null) {
            throw V2ApiException.badRequest("TRAVEL_ROUTE_UNAVAILABLE",
                    "当前位置与目的地之间没有可以直接通行的路线。", "先前往相邻地点");
        }
        String summary = "从%s前往目的地，耗时%d分钟"
                .formatted(aggregate.characterState().locationName(), edge.travelMinutes());
        String narrative = "你沿着已经存在的道路离开%s。路途耗去%d分钟，命卷中的位置随脚步一同改变。"
                .formatted(aggregate.characterState().locationName(), edge.travelMinutes());
        String intent = "{\"type\":\"TRAVEL\",\"targetLocationId\":\"%s\"}"
                .formatted(payload.targetLocationId());
        return new Resolution(edge.travelMinutes(), payload.targetLocationId(), intent, summary, narrative,
                "路线与交通耗时共同决定");
    }

    private ActionPayload decode(String payload) {
        try {
            return objectMapper.readValue(payload, ActionPayload.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid action payload", error);
        }
    }

    private record Resolution(long elapsedMinutes, String targetLocationId, String intentJson,
                              String summary, String narrative, String timeReason) {
    }
}

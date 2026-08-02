package com.wenxian.v2.api;

import com.wenxian.v2.account.SharedAccountSessionService;
import com.wenxian.v2.account.SharedAccountSessionService.Account;
import com.wenxian.v2.config.V2Properties;
import com.wenxian.v2.game.application.SaveApplicationService;
import com.wenxian.v2.game.application.SaveApplicationService.CreateSaveCommand;
import com.wenxian.v2.game.application.TurnApplicationService;
import com.wenxian.v2.game.application.TurnApplicationService.SubmitActionCommand;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import com.wenxian.v2.game.persistence.LocationRepository.KnownLocation;
import com.wenxian.v2.game.persistence.TurnRepository.Operation;
import com.wenxian.v2.game.persistence.TurnRepository.TurnSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2")
public class V2GameController {
    private final SharedAccountSessionService sessions;
    private final SaveApplicationService saves;
    private final TurnApplicationService turns;
    private final V2Properties properties;

    public V2GameController(SharedAccountSessionService sessions, SaveApplicationService saves,
                            TurnApplicationService turns, V2Properties properties) {
        this.sessions = sessions;
        this.saves = saves;
        this.turns = turns;
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "version", properties.engineVersion(), "persistence", "mysql-flyway",
                "ai", "not-connected");
    }

    @GetMapping("/bootstrap")
    public Map<String, Object> bootstrap() {
        return Map.of(
                "engineVersion", properties.engineVersion(),
                "contentVersion", properties.contentVersion(),
                "region", "青岐州",
                "races", List.of("HUMAN", "DEMON", "SPIRIT"),
                "difficulties", List.of("NORMAL", "HARD"),
                "deterministicActions", List.of("WAIT", "TRAVEL"));
    }

    @PostMapping("/saves")
    public SaveAggregate createSave(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
            @Valid @RequestBody CreateSaveRequest request) {
        Account account = sessions.requireAccount(token);
        return saves.create(account.userId(), new CreateSaveCommand(request.name(), request.genderExpression(),
                request.race(), request.rootType(), request.rootDescription(), request.lifeGoal(),
                request.difficulty()));
    }

    @GetMapping("/saves")
    public List<SaveAggregate> listSaves(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token) {
        return saves.list(sessions.requireAccount(token).userId());
    }

    @GetMapping("/saves/{saveId}")
    public SaveAggregate getSave(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
            @PathVariable String saveId) {
        return saves.requireAggregate(saveId, sessions.requireAccount(token).userId());
    }

    @GetMapping("/saves/{saveId}/character")
    public SaveAggregate character(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
            @PathVariable String saveId) {
        return saves.requireAggregate(saveId, sessions.requireAccount(token).userId());
    }

    @GetMapping("/saves/{saveId}/world")
    public List<KnownLocation> world(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
            @PathVariable String saveId) {
        return saves.knownWorld(saveId, sessions.requireAccount(token).userId());
    }

    @GetMapping("/saves/{saveId}/chronicle")
    public List<TurnSummary> chronicle(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
            @PathVariable String saveId,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit) {
        return saves.chronicle(saveId, sessions.requireAccount(token).userId(), limit);
    }

    @PostMapping("/saves/{saveId}/actions")
    public Operation submitAction(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
            @PathVariable String saveId,
            @Valid @RequestBody ActionRequest request) {
        Account account = sessions.requireAccount(token);
        return turns.submit(account.userId(), saveId, new SubmitActionCommand(request.requestId(),
                request.expectedStateVersion(), request.actionType(), request.actionText(),
                request.durationMinutes(), request.targetLocationId()));
    }

    @GetMapping("/turn-operations/{operationId}")
    public Operation operation(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
            @PathVariable String operationId) {
        return turns.requireOperation(sessions.requireAccount(token).userId(), operationId);
    }

    public record CreateSaveRequest(
            @NotBlank @Size(max = 20) String name,
            @NotBlank @Size(max = 20) String genderExpression,
            @NotBlank @Size(max = 20) String race,
            @NotBlank @Size(max = 40) String rootType,
            @NotBlank @Size(max = 300) String rootDescription,
            @NotBlank @Size(max = 160) String lifeGoal,
            @NotBlank @Size(max = 20) String difficulty
    ) {
    }

    public record ActionRequest(
            @NotBlank @Size(max = 80) String requestId,
            @Min(0) long expectedStateVersion,
            @NotBlank @Size(max = 40) String actionType,
            @NotBlank @Size(max = 1000) String actionText,
            @Min(1) Long durationMinutes,
            @Size(max = 80) String targetLocationId
    ) {
    }
}

package com.wenxian.v2.api;

import com.wenxian.v2.account.SharedAccountSessionService;
import com.wenxian.v2.account.SharedAccountSessionService.Account;
import com.wenxian.v2.config.V2Properties;
import com.wenxian.v2.game.application.SaveApplicationService;
import com.wenxian.v2.game.application.SaveCapacityService;
import com.wenxian.v2.game.application.SaveApplicationService.CreateSaveCommand;
import com.wenxian.v2.game.application.OriginApplicationService;
import com.wenxian.v2.game.application.OriginApplicationService.OriginProposal;
import com.wenxian.v2.game.application.OriginApplicationService.OriginRequest;
import com.wenxian.v2.game.application.TurnApplicationService;
import com.wenxian.v2.game.application.TurnEventStreamService;
import com.wenxian.v2.game.application.TurnApplicationService.SubmitActionCommand;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import com.wenxian.v2.game.persistence.LocationRepository.KnownLocation;
import com.wenxian.v2.game.persistence.DomainEffectRepository;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2")
public class V2GameController {
    private final SharedAccountSessionService sessions;
    private final SaveApplicationService saves;
    private final TurnApplicationService turns;
    private final V2Properties properties;
    private final DomainEffectRepository domainEffects;
    private final OriginApplicationService origins;
    private final SaveCapacityService capacity;
    private final TurnEventStreamService turnEvents;
    private final com.wenxian.v2.config.AiProperties aiProperties;

    public V2GameController(SharedAccountSessionService sessions, SaveApplicationService saves,
                            TurnApplicationService turns, V2Properties properties,
                            DomainEffectRepository domainEffects, OriginApplicationService origins,
                            SaveCapacityService capacity, TurnEventStreamService turnEvents,
                            com.wenxian.v2.config.AiProperties aiProperties) {
        this.sessions = sessions;
        this.saves = saves;
        this.turns = turns;
        this.properties = properties;
        this.domainEffects = domainEffects;
        this.origins = origins;
        this.capacity = capacity;
        this.turnEvents = turnEvents;
        this.aiProperties = aiProperties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "version", properties.engineVersion(), "persistence", "mysql-flyway",
                "ai", aiProperties.enabled() ? "configured" : "disabled");
    }

    @GetMapping("/bootstrap")
    public Map<String, Object> bootstrap() {
        return Map.of(
                "engineVersion", properties.engineVersion(),
                "contentVersion", properties.contentVersion(),
                "maxActiveSaves", capacity.limit(),
                "region", "青岐州",
                "races", List.of("HUMAN", "DEMON", "SPIRIT"),
                "difficulties", List.of("NORMAL", "HARD"),
                "starterTalents", List.of("HUMAN", "DEMON", "SPIRIT").stream()
                        .flatMap(race -> domainEffects.activeAbilityDefinitions(com.wenxian.v2.game.domain.Race.valueOf(race)).stream()
                                .filter(ability -> "TALENT".equals(ability.abilityType())))
                        .distinct().toList(),
                "deterministicActions", List.of("WAIT", "TRAVEL"));
    }

    @PostMapping("/saves")
    public SaveAggregate createSave(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
            @Valid @RequestBody CreateSaveRequest request) {
        Account account = sessions.requireAccount(token);
        return saves.create(account.userId(), new CreateSaveCommand(request.name(), request.genderExpression(),
                request.race(), request.rootType(), request.rootDescription(), request.lifeGoal(),
                request.difficulty(), request.starterTalentDefinitionId(), request.originProposalId(),
                request.fateCandidateId()));
    }

    @PostMapping("/creation/fates")
    public OriginProposal generateFates(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
            @Valid @RequestBody OriginProposalRequest request) {
        Account account = sessions.requireAccount(token);
        return origins.generate(account.userId(), new OriginRequest(request.name(), request.genderExpression(),
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
            @RequestParam(required = false) @Min(1) Integer beforeTurn,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit) {
        return saves.chronicle(saveId, sessions.requireAccount(token).userId(), beforeTurn, limit);
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

    @GetMapping(value = "/turn-operations/{operationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter operationEvents(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
            @PathVariable String operationId) {
        return turnEvents.stream(sessions.requireAccount(token).userId(), operationId);
    }

    @GetMapping("/turn-operations/active")
    public Map<String, Object> activeOperation(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("operation", turns.activeOperation(sessions.requireAccount(token).userId()));
        return result;
    }

    @PostMapping("/saves/{saveId}/archive")
    public SaveAggregate archiveSave(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
            @PathVariable String saveId,
            @Valid @RequestBody ArchiveSaveRequest request) {
        return saves.archive(saveId, sessions.requireAccount(token).userId(), request.characterName());
    }

    @PostMapping("/saves/{saveId}/end")
    public SaveAggregate endLife(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
            @PathVariable String saveId,
            @Valid @RequestBody EndLifeRequest request) {
        return saves.endLife(saveId, sessions.requireAccount(token).userId(),
                request.characterName(), request.endingType());
    }

    public record CreateSaveRequest(
            @NotBlank @Size(max = 20) String name,
            @NotBlank @Size(max = 20) String genderExpression,
            @NotBlank @Size(max = 20) String race,
            @NotBlank @Size(max = 40) String rootType,
            @NotBlank @Size(max = 300) String rootDescription,
            @NotBlank @Size(max = 160) String lifeGoal,
            @NotBlank @Size(max = 20) String difficulty,
            @Size(max = 80) String starterTalentDefinitionId,
            @Size(max = 80) String originProposalId,
            @Size(max = 40) String fateCandidateId
    ) {
    }

    public record OriginProposalRequest(
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

    public record ArchiveSaveRequest(@NotBlank @Size(max = 20) String characterName) {
    }

    public record EndLifeRequest(
            @NotBlank @Size(max = 20) String characterName,
            @NotBlank @Size(max = 20) String endingType
    ) {
    }
}

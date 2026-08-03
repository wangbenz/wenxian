package com.wenxian.v2.game.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.v2.ai.application.ActionAdjudicator;
import com.wenxian.v2.ai.application.TurnNarrativeContextAssembler;
import com.wenxian.v2.ai.application.TurnProposalGenerator;
import com.wenxian.v2.ai.application.TurnProposalNormalizer;
import com.wenxian.v2.ai.domain.ActionAdjudication;
import com.wenxian.v2.ai.domain.IntentContext;
import com.wenxian.v2.ai.domain.TurnNarrativeContext;
import com.wenxian.v2.ai.domain.TurnProposal;
import com.wenxian.v2.ai.domain.TurnProposal.ConfirmedFactProposal;
import com.wenxian.v2.game.application.DomainEffectService.ValidatedEffect;
import com.wenxian.v2.game.application.HighImpactEffectService.ValidatedHighImpact;
import com.wenxian.v2.ai.domain.TurnProposal.Generated;
import com.wenxian.v2.ai.domain.TurnProposal.TurnChoice;
import com.wenxian.v2.api.V2ApiException;
import com.wenxian.v2.game.application.TurnApplicationService.ActionPayload;
import com.wenxian.v2.game.domain.GameRecords.Save;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import com.wenxian.v2.game.domain.PlayerActionType;
import com.wenxian.v2.game.domain.WorldCalendar;
import com.wenxian.v2.game.persistence.GameSaveRepository;
import com.wenxian.v2.game.persistence.LocationRepository;
import com.wenxian.v2.game.persistence.LocationRepository.TravelEdge;
import com.wenxian.v2.game.persistence.TurnRepository;
import com.wenxian.v2.game.persistence.TurnRepository.DomainChange;
import com.wenxian.v2.game.persistence.TurnRepository.Operation;
import com.wenxian.v2.game.persistence.TurnRepository.Turn;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class TurnResolutionService {
    private static final Logger log = LoggerFactory.getLogger(TurnResolutionService.class);
    private static final long MAX_EXPLICIT_MINUTES = 100L * WorldCalendar.MINUTES_PER_YEAR;
    private static final long MAX_INFERRED_MINUTES = WorldCalendar.MINUTES_PER_YEAR;
    private static final Set<String> OUTCOME_CODES = Set.of(
            "ACTION_PROGRESSED", "ACTION_SUCCEEDED", "ACTION_PARTIAL", "ACTION_FAILED", "INFORMATION_DISCOVERED");
    private static final Set<String> FACT_DOMAINS = Set.of("CHARACTER", "LOCATION", "EVENT", "KNOWLEDGE", "WORLD");

    private final GameSaveRepository saves;
    private final LocationRepository locations;
    private final TurnRepository turns;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final TurnNarrativeContextAssembler contexts;
    private final TurnProposalGenerator proposals;
    private final TurnProposalNormalizer proposalNormalizer;
    private final ActionAdjudicator adjudicator;
    private final DomainEffectService domainEffects;
    private final HighImpactEffectService highImpactEffects;
    private final AutonomousWorldService autonomousWorld;

    public TurnResolutionService(GameSaveRepository saves, LocationRepository locations,
                                 TurnRepository turns, ObjectMapper objectMapper,
                                 TransactionTemplate transactions, TurnNarrativeContextAssembler contexts,
                                 TurnProposalGenerator proposals, TurnProposalNormalizer proposalNormalizer,
                                 ActionAdjudicator adjudicator,
                                 DomainEffectService domainEffects,
                                 HighImpactEffectService highImpactEffects,
                                 AutonomousWorldService autonomousWorld) {
        this.saves = saves;
        this.locations = locations;
        this.turns = turns;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.contexts = contexts;
        this.proposals = proposals;
        this.proposalNormalizer = proposalNormalizer;
        this.adjudicator = adjudicator;
        this.domainEffects = domainEffects;
        this.highImpactEffects = highImpactEffects;
        this.autonomousWorld = autonomousWorld;
    }

    public void resolve(String operationId) {
        PreparedTurn prepared = transactions.execute(status -> prepare(operationId));
        if (prepared == null) return;

        Resolution resolution = switch (prepared.actionType()) {
            case WAIT -> waitResolution(prepared.aggregate(), prepared.payload());
            case TRAVEL -> travelResolution(prepared.aggregate(), prepared.payload());
            case FREE -> freeResolution(prepared);
        };

        transactions.executeWithoutResult(status -> commit(prepared, resolution));
    }

    private PreparedTurn prepare(String operationId) {
        Operation operation = turns.lock(operationId);
        if (operation == null || !"QUEUED".equals(operation.status())) return null;
        PlayerActionType actionType = PlayerActionType.valueOf(operation.actionType());
        Instant now = Instant.now();
        String stage = actionType == PlayerActionType.FREE ? "INTERPRETING" : "RESOLVING";
        turns.updateStage(operationId, stage, stage, now);

        Save save = saves.lockOwned(operation.saveId(), operation.userId());
        if (save == null) throw V2ApiException.notFound("SAVE_NOT_FOUND", "没有找到这份命卷。");
        if (save.stateVersion() != operation.expectedStateVersion()) {
            throw V2ApiException.conflict("STATE_VERSION_CONFLICT",
                    "命卷已经在其他页面发生变化。", "刷新当前命卷后重新提交");
        }
        SaveAggregate aggregate = saves.aggregate(save.id(), save.userId());
        return new PreparedTurn(operation, aggregate, actionType, decode(operation.actionPayload()));
    }

    private Resolution freeResolution(PreparedTurn prepared) {
        TurnNarrativeContext context = contexts.assemble(prepared.operation().userId(),
                prepared.operation().id(), prepared.operation().actionText(), prepared.aggregate());
        Generated generated = proposalNormalizer.normalize(context, proposals.generate(context));
        transactions.executeWithoutResult(status ->
                turns.updateStage(prepared.operation().id(), "RESOLVING", "RESOLVING", Instant.now()));

        ActionAdjudication adjudication = adjudicator.adjudicate(context.authority(), generated.proposal().intent());
        if (adjudication.decision() != ActionAdjudication.Decision.ACCEPT) {
            throw new V2ApiException(HttpStatus.CONFLICT, adjudication.code(), "BUSINESS",
                    adjudication.reason(), adjudication.suggestions());
        }
        return proposalResolution(prepared.aggregate(), context, generated);
    }

    private Resolution proposalResolution(SaveAggregate aggregate, TurnNarrativeContext context,
                                          Generated generated) {
        TurnProposal proposal = generated.proposal();
        String summary = requireText(proposal.outcomeSummary(), 2, 500, "outcomeSummary");
        String narrative = requireText(proposal.narrativeText(), 80, 20_000, "narrativeText");
        String outcomeCode = normalizeOutcomeCode(proposal.outcomeCode());
        List<TurnChoice> choices = validChoices(proposal.choices());
        List<ConfirmedFactProposal> facts = validFacts(context, proposal.confirmedFacts());
        List<ValidatedEffect> effects;
        try {
            effects = domainEffects.validate(aggregate, context, proposal.intent(), proposal.domainEffects());
        } catch (V2ApiException invalidEffect) {
            log.warn("V2 proposal effect rejected save={} actionType={} itemRefs={} abilityRefs={} effects={}",
                    aggregate.save().id(), proposal.intent().actionType(), proposal.intent().referencedItemIds(),
                    proposal.intent().referencedAbilityIds(), proposal.domainEffects().stream()
                            .map(effect -> "%s(entity=%s,definition=%s,currency=%s)".formatted(
                                    effect.effectType(), effect.entityId(), effect.definitionId(),
                                    effect.currencyCode()))
                            .toList());
            throw invalidEffect;
        }
        ValidatedHighImpact highImpact = highImpactEffects.validate(aggregate, proposal.intent(), outcomeCode,
                proposal.highImpact(), effects);
        if (highImpact.lifeEnding() != null && !choices.isEmpty()) {
            throw invalidOutput("choices");
        }

        String targetLocationId = null;
        long elapsedMinutes;
        switch (proposal.intent().actionType()) {
            case "WAIT" -> elapsedMinutes = requireExplicitDuration(explicitDuration(proposal.intent()));
            case "TRAVEL" -> {
                String target = proposal.intent().targetLocationId();
                TravelEdge edge = locations.directEdge(aggregate.characterState().locationId(), target);
                if (edge == null) {
                    throw V2ApiException.conflict("TRAVEL_ROUTE_UNAVAILABLE",
                            "当前位置与目的地之间没有可以直接通行的路线。", "先前往相邻地点");
                }
                elapsedMinutes = edge.travelMinutes();
                targetLocationId = target;
            }
            default -> elapsedMinutes = ordinaryElapsed(proposal);
        }
        return new Resolution(elapsedMinutes, targetLocationId, encode(proposal.intent()), outcomeCode,
                summary, narrative, choices, facts, effects, highImpact, "根据行动过程与统一世界时间结算",
                generated.providerCode(), generated.modelCode());
    }

    private void commit(PreparedTurn prepared, Resolution resolved) {
        Operation operation = turns.lock(prepared.operation().id());
        if (operation == null || terminal(operation.status())) return;
        Save save = saves.lockOwned(operation.saveId(), operation.userId());
        if (save == null) throw V2ApiException.notFound("SAVE_NOT_FOUND", "没有找到这份命卷。");
        if (save.stateVersion() != operation.expectedStateVersion()) {
            throw V2ApiException.conflict("STATE_VERSION_CONFLICT",
                    "命卷已经在推演期间发生变化，本轮没有写入。", "刷新当前命卷后重新提交");
        }

        Instant now = Instant.now();
        turns.updateStage(operation.id(), "COMMITTING", "COMMITTING", now);
        long beforeMinute = prepared.aggregate().worldMinute();
        long afterMinute = Math.addExact(beforeMinute, resolved.elapsedMinutes());
        long beforeVersion = save.stateVersion();
        long afterVersion = beforeVersion + 1;
        int turnNumber = save.turnNumber() + 1;
        String turnId = "v2-turn-" + UUID.randomUUID();

        saves.updateWorldMinute(save.id(), afterMinute, now);
        if (resolved.targetLocationId() != null) {
            saves.updateCharacterLocation(prepared.aggregate().protagonist().id(),
                    resolved.targetLocationId(), afterVersion, now);
            saves.revealStartingLocations(save.id(), resolved.targetLocationId(), afterMinute, now);
        }
        saves.advanceSave(save.id(), turnNumber, afterVersion, now);

        Turn turn = new Turn(turnId, operation.id(), save.id(), turnNumber, operation.actionText(),
                resolved.intentJson(), resolved.outcomeCode(), resolved.summary(), resolved.narrative(),
                encode(resolved.choices()), beforeMinute, afterMinute, beforeVersion, afterVersion,
                resolved.modelProvider(), resolved.modelCode(), now);
        turns.insertTurn(turn);
        int order = 1;
        turns.insertChange(new DomainChange("v2-change-" + UUID.randomUUID(), turnId, save.id(), order++,
                "TIME", save.id(), "WORLD_TIME_ADVANCED", String.valueOf(beforeMinute),
                String.valueOf(afterMinute), resolved.timeReason(), now));
        if (resolved.targetLocationId() != null) {
            turns.insertChange(new DomainChange("v2-change-" + UUID.randomUUID(), turnId, save.id(), order++,
                    "CHARACTER", prepared.aggregate().protagonist().id(), "LOCATION_CHANGED",
                    prepared.aggregate().characterState().locationId(), resolved.targetLocationId(),
                    "沿已存在路线完成移动", now));
        }
        for (ConfirmedFactProposal fact : resolved.confirmedFacts()) {
            turns.insertChange(new DomainChange("v2-change-" + UUID.randomUUID(), turnId, save.id(), order++,
                    fact.domainType(), fact.entityId(), "NARRATIVE_FACT_CONFIRMED", null, fact.fact(),
                    "本回已经成立并需要延续的事实", now));
        }
        List<DomainChange> commonChanges = domainEffects.apply(turnId, prepared.aggregate(),
                resolved.domainEffects(), afterMinute, afterVersion, now, order);
        for (DomainChange change : commonChanges) {
            turns.insertChange(change);
        }
        order += commonChanges.size();
        List<DomainChange> highImpactChanges = highImpactEffects.apply(turnId, prepared.aggregate(), resolved.highImpact(),
                afterMinute, afterVersion, now, order);
        for (DomainChange change : highImpactChanges) {
            turns.insertChange(change);
        }
        order += highImpactChanges.size();
        List<DomainChange> eventChanges = autonomousWorld.advanceDueEvents(
                turnId, save.id(), afterMinute, now, order);
        for (DomainChange change : eventChanges) {
            turns.insertChange(change);
        }
        order += eventChanges.size();
        for (DomainChange change : autonomousWorld.advanceDueSchedules(
                turnId, save.id(), afterMinute, now, order)) {
            turns.insertChange(change);
        }
        turns.succeed(operation.id(), turnId, now);
    }

    private Resolution waitResolution(SaveAggregate aggregate, ActionPayload payload) {
        long minutes = requireExplicitDuration(payload.durationMinutes());
        WorldCalendar.WorldDate target = WorldCalendar.fromWorldMinute(aggregate.worldMinute() + minutes);
        String summary = "在%s度过了%d分钟".formatted(aggregate.characterState().locationName(), minutes);
        String narrative = "你留在%s，让时间安静地向前流过。再抬眼时，命卷已经来到%s。"
                .formatted(aggregate.characterState().locationName(), target.display());
        return new Resolution(minutes, null, "{\"type\":\"WAIT\"}", "ACTION_SUCCEEDED", summary,
                narrative, List.of(), List.of(), List.of(), ValidatedHighImpact.empty(),
                "玩家明确选择等待", null, null);
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
        return new Resolution(edge.travelMinutes(), payload.targetLocationId(), intent, "ACTION_SUCCEEDED",
                summary, narrative, List.of(), List.of(), List.of(), ValidatedHighImpact.empty(),
                "路线与交通耗时共同决定", null, null);
    }

    private List<ConfirmedFactProposal> validFacts(TurnNarrativeContext context,
                                                   List<ConfirmedFactProposal> candidates) {
        Set<String> entityIds = new HashSet<>();
        IntentContext authority = context.authority();
        entityIds.add(authority.saveId());
        entityIds.add(authority.protagonist().id());
        authority.characterCandidates().forEach(entity -> entityIds.add(entity.id()));
        authority.locationCandidates().forEach(entity -> entityIds.add(entity.id()));
        authority.itemCandidates().forEach(entity -> entityIds.add(entity.id()));
        authority.abilityCandidates().forEach(entity -> entityIds.add(entity.id()));
        context.activeEvents().forEach(event -> entityIds.add(event.id()));

        List<ConfirmedFactProposal> valid = new ArrayList<>();
        for (ConfirmedFactProposal fact : candidates == null ? List.<ConfirmedFactProposal>of() : candidates) {
            String domain = fact.domainType() == null ? "" : fact.domainType().strip().toUpperCase(Locale.ROOT);
            String entityId = fact.entityId() == null ? "" : fact.entityId().strip();
            String text = fact.fact() == null ? "" : fact.fact().strip();
            if (!FACT_DOMAINS.contains(domain) || !entityIds.contains(entityId)
                    || text.length() < 2 || text.length() > 300) {
                throw invalidOutput("confirmedFacts");
            }
            valid.add(new ConfirmedFactProposal(domain, entityId, text));
            if (valid.size() == 4) break;
        }
        return List.copyOf(valid);
    }

    private List<TurnChoice> validChoices(List<TurnChoice> candidates) {
        if (candidates == null) return List.of();
        List<TurnChoice> result = new ArrayList<>();
        for (TurnChoice choice : candidates) {
            String title = choice.title() == null ? "" : choice.title().strip();
            String action = choice.actionText() == null ? "" : choice.actionText().strip();
            if (title.length() < 2 || title.length() > 60 || action.length() < 2 || action.length() > 300) continue;
            result.add(new TurnChoice(title, action));
            if (result.size() == 4) break;
        }
        return List.copyOf(result);
    }

    private long ordinaryElapsed(TurnProposal proposal) {
        Long explicitDuration = explicitDuration(proposal.intent());
        if (explicitDuration != null) {
            return requireExplicitDuration(explicitDuration);
        }
        if (proposal.elapsedMinutes() == null) return defaultElapsed(proposal.intent().actionType());
        long proposed = proposal.elapsedMinutes();
        if (proposed <= 0 || proposed > MAX_INFERRED_MINUTES) throw invalidOutput("elapsedMinutes");
        return proposed;
    }

    private Long explicitDuration(com.wenxian.v2.ai.domain.ActionIntent intent) {
        return WorldCalendar.durationMinutes(intent.durationAmount(), intent.durationUnit(),
                intent.durationMinutes());
    }

    private long defaultElapsed(String actionType) {
        return switch (actionType) {
            case "INTERACT", "USE_ITEM" -> 30;
            case "USE_ABILITY", "COMBAT" -> 60;
            case "INVESTIGATE", "TRADE" -> 120;
            case "CULTIVATE" -> 360;
            default -> 60;
        };
    }

    private long requireExplicitDuration(Long value) {
        long minutes = value == null ? 0 : value;
        if (minutes <= 0 || minutes > MAX_EXPLICIT_MINUTES) {
            throw V2ApiException.badRequest("WAIT_DURATION_INVALID", "行动时间需要在一分钟至一百年之间。");
        }
        return minutes;
    }

    private String normalizeOutcomeCode(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        return OUTCOME_CODES.contains(normalized) ? normalized : "ACTION_PROGRESSED";
    }

    private String requireText(String value, int minimum, int maximum, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() < minimum || normalized.length() > maximum) throw invalidOutput(field);
        return normalized;
    }

    private V2ApiException invalidOutput(String field) {
        return new V2ApiException(HttpStatus.BAD_GATEWAY, "AI_OUTPUT_INVALID", "SERVICE",
                "本轮推演结果不完整，命卷状态不会改变。", List.of("稍后使用同一行动重试", "问题字段：" + field));
    }

    private boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "REJECTED".equals(status) || "FAILED".equals(status);
    }

    private ActionPayload decode(String payload) {
        try {
            return objectMapper.readValue(payload, ActionPayload.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid action payload", error);
        }
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Unable to encode turn data", impossible);
        }
    }

    private record PreparedTurn(Operation operation, SaveAggregate aggregate,
                                PlayerActionType actionType, ActionPayload payload) {
    }

    private record Resolution(long elapsedMinutes, String targetLocationId, String intentJson,
                              String outcomeCode, String summary, String narrative,
                              List<TurnChoice> choices, List<ConfirmedFactProposal> confirmedFacts,
                              List<ValidatedEffect> domainEffects,
                              ValidatedHighImpact highImpact,
                              String timeReason, String modelProvider, String modelCode) {
    }
}

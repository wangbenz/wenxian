package com.wenxian.v2.game.application;

import com.wenxian.v2.ai.domain.ActionIntent;
import com.wenxian.v2.ai.domain.TurnProposal.AbilityLearningProposal;
import com.wenxian.v2.ai.domain.TurnProposal.BreakthroughProposal;
import com.wenxian.v2.ai.domain.TurnProposal.CharacterIntroductionProposal;
import com.wenxian.v2.ai.domain.TurnProposal.ConflictProposal;
import com.wenxian.v2.ai.domain.TurnProposal.HighImpactProposal;
import com.wenxian.v2.ai.domain.TurnProposal.LifeEndingProposal;
import com.wenxian.v2.ai.domain.TurnProposal.RelationshipChangeProposal;
import com.wenxian.v2.api.V2ApiException;
import com.wenxian.v2.game.application.DomainEffectService.ValidatedEffect;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import com.wenxian.v2.game.domain.Race;
import com.wenxian.v2.game.domain.RealmLadder;
import com.wenxian.v2.game.persistence.HighImpactRepository;
import com.wenxian.v2.game.persistence.HighImpactRepository.AbilityDefinition;
import com.wenxian.v2.game.persistence.HighImpactRepository.CharacterAuthority;
import com.wenxian.v2.game.persistence.TurnRepository.DomainChange;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class HighImpactEffectService {
    private static final Set<String> RELATIONSHIP_TYPES = Set.of(
            "ACQUAINTANCE", "ALLY", "RIVAL", "MENTOR", "DEBTOR", "CREDITOR", "ENEMY", "COMPANION");
    private static final Set<String> CONFLICT_RESULTS = Set.of("VICTORY", "DEFEAT", "ESCAPED", "STALEMATE");
    private static final Set<String> INTRODUCTION_ACTIONS = Set.of(
            "TRAVEL", "INVESTIGATE", "INTERACT", "TRADE", "COMBAT", "OTHER");

    private final HighImpactRepository repository;

    public HighImpactEffectService(HighImpactRepository repository) {
        this.repository = repository;
    }

    public ValidatedHighImpact validate(SaveAggregate aggregate, ActionIntent intent, String outcomeCode,
                                        HighImpactProposal proposal, List<ValidatedEffect> commonEffects) {
        if (proposal == null) return ValidatedHighImpact.empty();
        if (proposal.introducedCharacters().size() > 2) throw invalid("highImpact.introducedCharacters");
        if (proposal.relationshipChanges().size() > 4) throw invalid("highImpact.relationshipChanges");
        if (proposal.learnedAbilities().size() > 1) throw invalid("highImpact.learnedAbilities");

        Set<String> temporaryIds = new HashSet<>();
        Set<String> introducedNames = new HashSet<>();
        List<ValidatedIntroduction> introductions = new ArrayList<>();
        if (!proposal.introducedCharacters().isEmpty()
                && !INTRODUCTION_ACTIONS.contains(intent.actionType())) {
            throw invalid("highImpact.introducedCharacters.actionType");
        }
        for (CharacterIntroductionProposal candidate : proposal.introducedCharacters()) {
            String temporaryId = requireTempId(candidate.temporaryId());
            String name = requireText(candidate.name(), 1, 20, "highImpact.introducedCharacters.name");
            if (!temporaryIds.add(temporaryId) || !introducedNames.add(name)
                    || repository.characterNameExists(aggregate.save().id(), name)) {
                throw invalid("highImpact.introducedCharacters.identity");
            }
            Race race = parseRace(candidate.race());
            var initialRealm = RealmLadder.initial(race);
            introductions.add(new ValidatedIntroduction(temporaryId, name,
                    requireText(candidate.genderExpression(), 1, 16, "highImpact.introducedCharacters.gender"),
                    race, requireText(candidate.rootType(), 1, 40, "highImpact.introducedCharacters.rootType"),
                    requireText(candidate.description(), 4, 300, "highImpact.introducedCharacters.description"),
                    requireText(candidate.goal(), 2, 160, "highImpact.introducedCharacters.goal"),
                    requireText(candidate.publicIdentity(), 2, 100,
                            "highImpact.introducedCharacters.publicIdentity"),
                    relationshipType(candidate.relationshipType()), initialRealm.code(), initialRealm.displayName()));
        }

        Set<String> existingNpcIds = new HashSet<>();
        aggregate.knownCharacters().stream().filter(character -> "ALIVE".equals(character.status()))
                .forEach(character -> existingNpcIds.add(character.id()));
        List<ValidatedRelationship> relationships = new ArrayList<>();
        Set<String> relationshipKeys = new HashSet<>();
        for (RelationshipChangeProposal candidate : proposal.relationshipChanges()) {
            String target = stripped(candidate.targetEntityId());
            if (!existingNpcIds.contains(target) && !temporaryIds.contains(target)) {
                throw invalid("highImpact.relationshipChanges.targetEntityId");
            }
            String type = relationshipType(candidate.relationshipType());
            int trust = delta(candidate.trustDelta(), "trustDelta");
            int closeness = delta(candidate.closenessDelta(), "closenessDelta");
            int fear = delta(candidate.fearDelta(), "fearDelta");
            int interest = delta(candidate.interestDelta(), "interestDelta");
            if (trust == 0 && closeness == 0 && fear == 0 && interest == 0) {
                throw invalid("highImpact.relationshipChanges.delta");
            }
            if (!relationshipKeys.add(target + ":" + type)) {
                throw invalid("highImpact.relationshipChanges.duplicate");
            }
            relationships.add(new ValidatedRelationship(target, type, trust, closeness, fear, interest,
                    requireText(candidate.summary(), 2, 240, "highImpact.relationshipChanges.summary")));
        }

        Set<String> sourceIds = new HashSet<>(existingNpcIds);
        sourceIds.addAll(temporaryIds);
        aggregate.inventory().forEach(item -> sourceIds.add(item.instanceId()));
        List<ValidatedAbilityLearning> learnings = new ArrayList<>();
        for (AbilityLearningProposal candidate : proposal.learnedAbilities()) {
            AbilityDefinition definition = repository.abilityDefinition(stripped(candidate.definitionId()));
            if (definition == null || !("ANY".equals(definition.raceScope())
                    || aggregate.protagonist().race().name().equals(definition.raceScope()))
                    || repository.alreadyLearned(aggregate.save().id(), aggregate.protagonist().id(), definition.id())
                    || !("CULTIVATE".equals(intent.actionType()) || "INTERACT".equals(intent.actionType())
                    || "INVESTIGATE".equals(intent.actionType()))) {
                throw invalid("highImpact.learnedAbilities.definitionId");
            }
            String sourceId = stripped(candidate.sourceEntityId());
            if (!sourceIds.contains(sourceId)) throw invalid("highImpact.learnedAbilities.sourceEntityId");
            learnings.add(new ValidatedAbilityLearning(definition, sourceId,
                    requireText(candidate.reason(), 2, 240, "highImpact.learnedAbilities.reason")));
        }

        ValidatedBreakthrough breakthrough = validateBreakthrough(aggregate, intent, outcomeCode,
                proposal.breakthrough(), commonEffects);
        ValidatedConflict conflict = validateConflict(aggregate, intent, proposal.conflict());
        ValidatedLifeEnding ending = validateEnding(intent, outcomeCode, conflict, proposal.lifeEnding());
        return new ValidatedHighImpact(List.copyOf(introductions), List.copyOf(relationships),
                List.copyOf(learnings), breakthrough, conflict, ending);
    }

    private ValidatedBreakthrough validateBreakthrough(SaveAggregate aggregate, ActionIntent intent,
                                                       String outcomeCode, BreakthroughProposal candidate,
                                                       List<ValidatedEffect> commonEffects) {
        if (candidate == null) return null;
        String target = stripped(candidate.targetRealmCode()).toUpperCase(Locale.ROOT);
        boolean gainedInsight = commonEffects.stream().anyMatch(effect -> "CULTIVATION_INSIGHT".equals(effect.type()));
        boolean prepared = "PRACTICING".equals(aggregate.cultivation().projectStage())
                && !stripped(aggregate.cultivation().insightSummary()).isEmpty();
        if (!"CULTIVATE".equals(intent.actionType()) || !target.equals(stripped(intent.targetRealmCode()))
                || !RealmLadder.isAdjacent(aggregate.protagonist().race(), aggregate.characterState().realmCode(), target)
                || !("ACTION_SUCCEEDED".equals(outcomeCode) || "ACTION_PROGRESSED".equals(outcomeCode))
                || (!prepared && !gainedInsight)) {
            throw invalid("highImpact.breakthrough");
        }
        var realm = RealmLadder.byCode(aggregate.protagonist().race(), target);
        return new ValidatedBreakthrough(target, realm.displayName(),
                requireText(candidate.reason(), 2, 300, "highImpact.breakthrough.reason"));
    }

    private ValidatedConflict validateConflict(SaveAggregate aggregate, ActionIntent intent,
                                               ConflictProposal candidate) {
        if (candidate == null) return null;
        String targetId = stripped(candidate.targetCharacterId());
        CharacterAuthority target = repository.character(aggregate.save().id(), targetId);
        String result = normalized(candidate.result());
        if (!"COMBAT".equals(intent.actionType()) || target == null || !"NPC".equals(target.kind())
                || !"ALIVE".equals(target.status()) || !target.locationId().equals(aggregate.characterState().locationId())
                || !CONFLICT_RESULTS.contains(result)) {
            throw invalid("highImpact.conflict");
        }
        int protagonistRank = RealmLadder.rank(aggregate.protagonist().race(), aggregate.characterState().realmCode());
        int targetRank = RealmLadder.rank(target.race(), target.realmCode());
        if ("VICTORY".equals(result) && targetRank >= protagonistRank + 2) {
            throw invalid("highImpact.conflict.realmBoundary");
        }
        return new ValidatedConflict(target, result,
                requireText(candidate.reason(), 2, 300, "highImpact.conflict.reason"));
    }

    private ValidatedLifeEnding validateEnding(ActionIntent intent, String outcomeCode,
                                               ValidatedConflict conflict, LifeEndingProposal candidate) {
        if (candidate == null) return null;
        if (!"COMBAT".equals(intent.actionType()) || conflict == null || !"DEFEAT".equals(conflict.result())
                || !"ACTION_FAILED".equals(outcomeCode)) {
            throw invalid("highImpact.lifeEnding");
        }
        return new ValidatedLifeEnding(
                requireText(candidate.cause(), 2, 120, "highImpact.lifeEnding.cause"),
                requireText(candidate.epilogue(), 8, 500, "highImpact.lifeEnding.epilogue"));
    }

    public List<DomainChange> apply(String turnId, SaveAggregate aggregate, ValidatedHighImpact validated,
                                    long worldMinute, long stateVersion, Instant now, int firstOrder) {
        if (validated == null || validated.emptyValue()) return List.of();
        List<DomainChange> changes = new ArrayList<>();
        Map<String, String> resolvedIds = new HashMap<>();
        int order = firstOrder;
        for (ValidatedIntroduction character : validated.introductions()) {
            String id = repository.insertNpc(aggregate.save().id(), character.name(), character.genderExpression(),
                    character.race(), character.rootType(), character.description(), character.goal(),
                    character.publicIdentity(), aggregate.characterState().locationId(), character.realmCode(),
                    character.realmDisplayName(), worldMinute, now);
            resolvedIds.put(character.temporaryId(), id);
            changes.add(change(turnId, aggregate.save().id(), order++, "CHARACTER", id,
                    "CHARACTER_INTRODUCED", null, character.name() + "·" + character.publicIdentity(),
                    "本回正式登场并进入持续人物档案", now));
            var relation = repository.changeRelationship(aggregate.save().id(), aggregate.protagonist().id(), id,
                    character.relationshipType(), 0, 0, 0, 1, "本回初次相识", worldMinute, now);
            changes.add(change(turnId, aggregate.save().id(), order++, "RELATIONSHIP", relation.id(),
                    "RELATIONSHIP_ESTABLISHED", "陌生", character.relationshipType(), "本回初次相识", now));
        }
        for (ValidatedRelationship relationship : validated.relationships()) {
            String targetId = resolvedIds.getOrDefault(relationship.targetRef(), relationship.targetRef());
            var mutation = repository.changeRelationship(aggregate.save().id(), aggregate.protagonist().id(),
                    targetId, relationship.type(), relationship.trustDelta(), relationship.closenessDelta(),
                    relationship.fearDelta(), relationship.interestDelta(), relationship.summary(), worldMinute, now);
            changes.add(change(turnId, aggregate.save().id(), order++, "RELATIONSHIP", mutation.id(),
                    "RELATIONSHIP_CHANGED", relationshipBefore(mutation), relationshipAfter(mutation),
                    relationship.summary(), now));
        }
        for (ValidatedAbilityLearning learning : validated.learnings()) {
            String sourceId = resolvedIds.getOrDefault(learning.sourceRef(), learning.sourceRef());
            String sourceType = sourceId.startsWith("v2-item-") ? "ITEM" : "CHARACTER";
            String abilityId = repository.learnAbility(aggregate.save().id(), aggregate.protagonist().id(),
                    learning.definition().id(), sourceType, sourceId, worldMinute, now);
            changes.add(change(turnId, aggregate.save().id(), order++, "ABILITY", abilityId,
                    "ABILITY_LEARNED", null, learning.definition().name() + "·初识", learning.reason(), now));
        }
        if (validated.breakthrough() != null) {
            repository.breakthrough(aggregate.save().id(), aggregate.protagonist().id(),
                    validated.breakthrough().targetRealmCode(), validated.breakthrough().targetRealmName(),
                    validated.breakthrough().reason(), stateVersion, now);
            changes.add(change(turnId, aggregate.save().id(), order++, "CULTIVATION",
                    aggregate.protagonist().id(), "REALM_BREAKTHROUGH",
                    aggregate.characterState().realmDisplayName(), validated.breakthrough().targetRealmName(),
                    validated.breakthrough().reason(), now));
        }
        if (validated.conflict() != null) {
            changes.add(change(turnId, aggregate.save().id(), order++, "CONFLICT",
                    validated.conflict().target().id(), "CONFLICT_RESOLVED", null,
                    validated.conflict().result(), validated.conflict().reason(), now));
        }
        if (validated.lifeEnding() != null) {
            repository.endProtagonist(aggregate.save().id(), aggregate.protagonist().id(),
                    validated.lifeEnding().cause(), stateVersion, now);
            changes.add(change(turnId, aggregate.save().id(), order, "CHARACTER",
                    aggregate.protagonist().id(), "LIFE_ENDED", "ALIVE", "DEAD",
                    validated.lifeEnding().cause() + "；" + validated.lifeEnding().epilogue(), now));
        }
        return List.copyOf(changes);
    }

    private int delta(Integer value, String field) {
        int result = value == null ? 0 : value;
        if (result < -2 || result > 2) throw invalid("highImpact.relationshipChanges." + field);
        return result;
    }

    private String relationshipType(String value) {
        String result = normalized(value);
        if (!RELATIONSHIP_TYPES.contains(result)) throw invalid("highImpact.relationshipType");
        return result;
    }

    private Race parseRace(String value) {
        try {
            return Race.valueOf(normalized(value));
        } catch (IllegalArgumentException error) {
            throw invalid("highImpact.introducedCharacters.race");
        }
    }

    private String requireTempId(String value) {
        String result = normalized(value);
        if (!result.matches("NEW_NPC_[1-2]")) throw invalid("highImpact.introducedCharacters.temporaryId");
        return result;
    }

    private String requireText(String value, int minimum, int maximum, String field) {
        String result = stripped(value);
        if (result.length() < minimum || result.length() > maximum) throw invalid(field);
        return result;
    }

    private String normalized(String value) {
        return stripped(value).toUpperCase(Locale.ROOT);
    }

    private String stripped(String value) {
        return value == null ? "" : value.strip();
    }

    private V2ApiException invalid(String field) {
        return new V2ApiException(HttpStatus.BAD_GATEWAY, "AI_HIGH_IMPACT_INVALID", "SERVICE",
                "本轮重大变化没有通过命卷一致性检查，已有状态不会改变。",
                List.of("稍后使用同一行动重试", "问题字段：" + field));
    }

    private DomainChange change(String turnId, String saveId, int order, String domainType,
                                String entityId, String changeType, String before, String after,
                                String reason, Instant now) {
        return new DomainChange("v2-change-" + UUID.randomUUID(), turnId, saveId, order,
                domainType, entityId, changeType, before, after, reason, now);
    }

    private String relationshipBefore(HighImpactRepository.RelationshipMutation value) {
        return "%d/%d/%d/%d".formatted(value.beforeTrust(), value.beforeCloseness(),
                value.beforeFear(), value.beforeInterest());
    }

    private String relationshipAfter(HighImpactRepository.RelationshipMutation value) {
        return "%d/%d/%d/%d".formatted(value.afterTrust(), value.afterCloseness(),
                value.afterFear(), value.afterInterest());
    }

    public record ValidatedHighImpact(List<ValidatedIntroduction> introductions,
                                      List<ValidatedRelationship> relationships,
                                      List<ValidatedAbilityLearning> learnings,
                                      ValidatedBreakthrough breakthrough,
                                      ValidatedConflict conflict,
                                      ValidatedLifeEnding lifeEnding) {
        public static ValidatedHighImpact empty() {
            return new ValidatedHighImpact(List.of(), List.of(), List.of(), null, null, null);
        }

        public boolean emptyValue() {
            return introductions.isEmpty() && relationships.isEmpty() && learnings.isEmpty()
                    && breakthrough == null && conflict == null && lifeEnding == null;
        }
    }

    public record ValidatedIntroduction(String temporaryId, String name, String genderExpression, Race race,
                                        String rootType, String description, String goal, String publicIdentity,
                                        String relationshipType, String realmCode, String realmDisplayName) {
    }

    public record ValidatedRelationship(String targetRef, String type, int trustDelta, int closenessDelta,
                                        int fearDelta, int interestDelta, String summary) {
    }

    public record ValidatedAbilityLearning(AbilityDefinition definition, String sourceRef, String reason) {
    }

    public record ValidatedBreakthrough(String targetRealmCode, String targetRealmName, String reason) {
    }

    public record ValidatedConflict(CharacterAuthority target, String result, String reason) {
    }

    public record ValidatedLifeEnding(String cause, String epilogue) {
    }
}

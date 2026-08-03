package com.wenxian.v2.ai.domain;

import java.math.BigDecimal;
import java.util.List;

public record TurnProposal(
        ActionIntent intent,
        Long elapsedMinutes,
        String outcomeCode,
        String outcomeSummary,
        String narrativeText,
        List<ConfirmedFactProposal> confirmedFacts,
        List<DomainEffectProposal> domainEffects,
        HighImpactProposal highImpact,
        List<TurnChoice> choices
) {
    public TurnProposal {
        confirmedFacts = confirmedFacts == null ? List.of() : List.copyOf(confirmedFacts);
        domainEffects = domainEffects == null ? List.of() : List.copyOf(domainEffects);
        highImpact = highImpact == null ? HighImpactProposal.empty() : highImpact;
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    public TurnProposal(ActionIntent intent, Long elapsedMinutes, String outcomeCode,
                        String outcomeSummary, String narrativeText,
                        List<ConfirmedFactProposal> confirmedFacts,
                        List<DomainEffectProposal> domainEffects, List<TurnChoice> choices) {
        this(intent, elapsedMinutes, outcomeCode, outcomeSummary, narrativeText,
                confirmedFacts, domainEffects, HighImpactProposal.empty(), choices);
    }

    public TurnProposal(ActionIntent intent, Long elapsedMinutes, String outcomeCode,
                        String outcomeSummary, String narrativeText,
                        List<ConfirmedFactProposal> confirmedFacts, List<TurnChoice> choices) {
        this(intent, elapsedMinutes, outcomeCode, outcomeSummary, narrativeText,
                confirmedFacts, List.of(), HighImpactProposal.empty(), choices);
    }

    public record ConfirmedFactProposal(String domainType, String entityId, String fact) {
    }

    public record DomainEffectProposal(
            String effectType,
            String entityId,
            String definitionId,
            String currencyCode,
            BigDecimal amount,
            String value,
            String reason
    ) {
    }

    public record HighImpactProposal(
            List<CharacterIntroductionProposal> introducedCharacters,
            List<RelationshipChangeProposal> relationshipChanges,
            List<AbilityLearningProposal> learnedAbilities,
            BreakthroughProposal breakthrough,
            ConflictProposal conflict,
            LifeEndingProposal lifeEnding
    ) {
        public HighImpactProposal {
            introducedCharacters = introducedCharacters == null ? List.of() : List.copyOf(introducedCharacters);
            relationshipChanges = relationshipChanges == null ? List.of() : List.copyOf(relationshipChanges);
            learnedAbilities = learnedAbilities == null ? List.of() : List.copyOf(learnedAbilities);
        }

        public static HighImpactProposal empty() {
            return new HighImpactProposal(List.of(), List.of(), List.of(), null, null, null);
        }
    }

    public record CharacterIntroductionProposal(
            String temporaryId,
            String name,
            String genderExpression,
            String race,
            String rootType,
            String description,
            String goal,
            String publicIdentity,
            String relationshipType
    ) {
    }

    public record RelationshipChangeProposal(
            String targetEntityId,
            String relationshipType,
            Integer trustDelta,
            Integer closenessDelta,
            Integer fearDelta,
            Integer interestDelta,
            String summary
    ) {
    }

    public record AbilityLearningProposal(
            String definitionId,
            String sourceEntityId,
            String reason
    ) {
    }

    public record BreakthroughProposal(String targetRealmCode, String reason) {
    }

    public record ConflictProposal(String targetCharacterId, String result, String reason) {
    }

    public record LifeEndingProposal(String cause, String epilogue) {
    }

    public record TurnChoice(String title, String actionText) {
    }

    public record Generated(TurnProposal proposal, String providerCode, String modelCode) {
    }
}

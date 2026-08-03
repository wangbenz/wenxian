package com.wenxian.v2.game.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class GameRecords {
    private GameRecords() {
    }

    public record Save(
            String id,
            String userId,
            int slotNo,
            String status,
            Difficulty difficulty,
            String protagonistId,
            String engineVersion,
            String contentVersion,
            long stateVersion,
            int turnNumber,
            String endReason,
            Instant createdAt,
            Instant updatedAt,
            Instant endedAt
    ) {
    }

    public record Character(
            String id,
            String saveId,
            String name,
            String genderExpression,
            Race race,
            String rootType,
            String rootDescription,
            String lifeGoal,
            String status
    ) {
    }

    public record CharacterState(
            String characterId,
            String locationId,
            String locationName,
            String lifeStageCode,
            String realmCode,
            String realmDisplayName,
            String realmPhase,
            String publicIdentity,
            String bodyCondition,
            String currentGoal,
            long stateVersion
    ) {
    }

    public record CharacterOrigin(
            String fateTitle,
            String publicIdentity,
            String openingSituation,
            String unpaidDebt
    ) {
    }

    public record WalletBalance(String currencyCode, String currencyName, BigDecimal balance) {
    }

    public record InventoryItem(
            String instanceId,
            String definitionId,
            String itemCode,
            String name,
            BigDecimal quantity,
            String status,
            String condition,
            String provenance
    ) {
    }

    public record Ability(
            String characterAbilityId,
            String abilityCode,
            String name,
            String type,
            String sourceType,
            String sourceReferenceId,
            String masteryStage,
            String stability,
            String status,
            long learnedWorldMinute
    ) {
    }

    public record CultivationProgress(
            String pathCode,
            String pathName,
            String currentRealmCode,
            String currentRealmPhase,
            String targetRealmCode,
            String projectStage,
            String insightSummary,
            long stateVersion
    ) {
    }

    public record WorldEvent(
            String id,
            String title,
            String scale,
            String stage,
            String locationId,
            String locationName,
            String publicSummary
    ) {
    }

    public record KnownCharacter(
            String id,
            String name,
            Race race,
            String status,
            String publicIdentity,
            String locationId,
            String locationName,
            String realmCode,
            String realmDisplayName,
            String currentGoal
    ) {
    }

    public record Relationship(
            String id,
            String targetCharacterId,
            String targetName,
            String relationshipType,
            int trustLevel,
            int closenessLevel,
            int fearLevel,
            int interestLevel,
            String debtSummary,
            String conflictSummary,
            String sharedSecretSummary,
            long lastChangedWorldMinute
    ) {
    }

    public record LifeEnding(
            String endingType,
            String title,
            String summary,
            String finalRealmCode,
            String finalRealmDisplayName,
            String finalLocationId,
            int finalTurnNumber,
            long endedWorldMinute,
            Instant createdAt
    ) {
    }

    public record SaveAggregate(
            Save save,
            Character protagonist,
            CharacterState characterState,
            CharacterOrigin origin,
            long worldMinute,
            WorldCalendar.WorldDate worldDate,
            List<WalletBalance> wallet,
            List<InventoryItem> inventory,
            List<Ability> abilities,
            CultivationProgress cultivation,
            List<WorldEvent> worldEvents,
            List<KnownCharacter> knownCharacters,
            List<Relationship> relationships,
            LifeEnding ending
    ) {
    }
}

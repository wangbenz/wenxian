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
            Instant createdAt,
            Instant updatedAt
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

    public record WalletBalance(String currencyCode, String currencyName, BigDecimal balance) {
    }

    public record InventoryItem(
            String instanceId,
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
            String masteryStage,
            String stability,
            String status
    ) {
    }

    public record SaveAggregate(
            Save save,
            Character protagonist,
            CharacterState characterState,
            long worldMinute,
            WorldCalendar.WorldDate worldDate,
            List<WalletBalance> wallet,
            List<InventoryItem> inventory,
            List<Ability> abilities
    ) {
    }
}

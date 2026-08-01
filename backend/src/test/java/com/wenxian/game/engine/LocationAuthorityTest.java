package com.wenxian.game.engine;

import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocationAuthorityTest {
    @Test
    void legacySaveReceivesStableCurrentLocationRecord() {
        GameState state = new GameState();
        state.currentLocation = "青木镇后巷";

        assertTrue(LocationAuthority.repairLegacyState(state));
        assertNotNull(state.currentLocationId);
        assertEquals(1, state.knownLocations.size());
        assertEquals(state.currentLocationId, state.knownLocations.getFirst().id);
    }

    @Test
    void rejectsUnexplainedLocationJump() {
        GameState state = state();
        assertThrows(LocationAuthority.LocationValidationException.class,
                () -> LocationAuthority.validate(null, state, "北荒古城", 30, intent(List.of())));
    }

    @Test
    void keepsCanonicalLocationWhenExplorationOnlyUsesAMorePreciseSceneLabel() {
        GameState state = state();
        ActionIntent explore = new ActionIntent("EXPLORE", null, null, null, "UNKNOWN", List.of(),
                "LOW", false, List.of());

        assertNull(LocationAuthority.validate(null, state, "青木镇坊市深巷", 20, explore));
        assertFalse(LocationAuthority.requiresTransitionRepair(null, state, "青木镇坊市深巷", explore));
    }

    @Test
    void requestsSmallStructureRepairOnlyForRealTravel() {
        GameState state = state();

        assertTrue(LocationAuthority.requiresTransitionRepair(null, state, "北城门", intent(List.of())));
    }

    @Test
    void acceptsAuditableRegionalTravelAndRejectsUnownedTeleport() {
        GameState state = state();
        AgentResult.LocationTransition road = new AgentResult.LocationTransition(
                state.currentLocationId, "location-north-gate", "北城门", "REGIONAL",
                "RIDE", null, "沿官道骑行抵达");
        AgentResult.LocationTransition validated = LocationAuthority.validate(
                road, state, "北城门", 180, intent(List.of()));
        assertEquals("北城门", validated.toLocationName());
        assertEquals(LocationAuthority.idFor("北城门"), validated.toLocationId());

        AgentResult.LocationTransition teleport = new AgentResult.LocationTransition(
                state.currentLocationId, "location-secret", "秘境", "SPECIAL",
                "TELEPORT", "void-step", "借破空术进入");
        assertThrows(IllegalArgumentException.class,
                () -> LocationAuthority.validate(teleport, state, "秘境", 1, intent(List.of())));
        assertEquals("秘境", LocationAuthority.validate(teleport, state, "秘境", 1,
                intent(List.of("void-step"))).toLocationName());
    }

    @Test
    void normalizesDifferentNamesForTheSameAgentReportedDestination() {
        GameState state = state();
        AgentResult.LocationTransition transition = new AgentResult.LocationTransition(
                state.currentLocationId, "location-gate", "青铜门后", "LOCAL",
                "WALK", null, "穿过青铜门查看内室");

        AgentResult.LocationTransition validated = LocationAuthority.validate(
                transition, state, "青铜门祭坛内室", 10, intent(List.of()));

        assertEquals("青铜门祭坛内室", validated.toLocationName());
        assertEquals(LocationAuthority.idFor("青铜门祭坛内室"), validated.toLocationId());
    }

    private GameState state() {
        GameState state = new GameState();
        state.currentLocation = "青木镇";
        LocationAuthority.repairLegacyState(state);
        return state;
    }

    private ActionIntent intent(List<String> abilities) {
        return new ActionIntent("TRAVEL", null, null, null, "UNKNOWN", abilities,
                "LOW", false, List.of());
    }
}

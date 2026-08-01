package com.wenxian.game.engine;

import com.wenxian.game.model.GameState;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class LocationAuthority {
    private static final Set<String> SCOPES = Set.of("LOCAL", "SETTLEMENT", "REGIONAL", "CROSS_REGION", "SPECIAL");
    private static final Set<String> METHODS = Set.of("WALK", "RIDE", "VESSEL", "ESCAPE", "FORCED", "TELEPORT", "OTHER");

    private LocationAuthority() {}

    public static boolean repairLegacyState(GameState state) {
        if (state == null) return false;
        boolean changed = false;
        if (state.knownLocations == null) {
            state.knownLocations = new java.util.ArrayList<>();
            changed = true;
        }
        String locationName = usable(state.currentLocation) ? state.currentLocation.strip() : "未知之地";
        String expectedId = idFor(locationName);
        boolean currentIdMatchesName = usable(state.currentLocationId) && state.knownLocations.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(location -> state.currentLocationId.equals(location.id) && locationName.equals(location.name));
        if (!currentIdMatchesName) {
            state.currentLocationId = expectedId;
            changed = true;
        }
        if (state.knownLocations.stream().filter(java.util.Objects::nonNull)
                .noneMatch(location -> state.currentLocationId.equals(location.id))) {
            state.knownLocations.add(new GameState.LocationRecord(
                    state.currentLocationId, locationName, "LOCAL", null, absoluteTime(state)));
            changed = true;
        }
        return changed;
    }

    public static AgentResult.LocationTransition validate(
            AgentResult.LocationTransition transition, GameState state, String destination, int elapsed,
            ActionIntent intent) {
        repairLegacyState(state);
        String proposedName = usable(destination) ? destination.strip() : state.currentLocation;
        if (transition == null) {
            if (proposedName.equals(state.currentLocation)) return null;
            if (requiresMovementEvidence(intent)) {
                throw new LocationValidationException("LOCATION_TRANSITION_MISSING", "Agent 改变地点但没有提交迁移凭证");
            }
            // currentLocation is an authoritative world position, not a scene caption.
            // A non-travel chapter may call a stall, room or alley by a more precise
            // name; the caller keeps the canonical location and may use that label as
            // sceneTitle instead of failing the whole chapter.
            return null;
        }
        String finalName = !proposedName.equals(state.currentLocation)
                ? proposedName
                : usable(transition.toLocationName()) ? transition.toLocationName().strip() : state.currentLocation;
        if (finalName.equals(state.currentLocation)) return null;
        if (!state.currentLocationId.equals(transition.fromLocationId())) {
            throw new LocationValidationException("LOCATION_ORIGIN_MISMATCH", "地点迁移起点与当前存档不一致");
        }
        // The chapter location is player-facing and usually more precise than the
        // accounting payload (for example "青铜门内室" vs "青铜门后"). Keep the
        // authoritative chapter location and normalize the transition to it instead
        // of spending another model call on a harmless naming difference.
        String scope = SCOPES.contains(transition.scope()) ? transition.scope() : null;
        String method = METHODS.contains(transition.method()) ? transition.method() : null;
        if (scope == null || method == null || !usable(transition.reason())) {
            throw new LocationValidationException("LOCATION_TRANSITION_INVALID", "地点迁移凭证字段不完整");
        }
        int minimumElapsed = switch (scope) {
            case "SETTLEMENT" -> 10;
            case "REGIONAL" -> 60;
            case "CROSS_REGION" -> 360;
            default -> 1;
        };
        if (elapsed < minimumElapsed && !"TELEPORT".equals(method)) {
            throw new LocationValidationException("LOCATION_ELAPSED_INVALID", "地点跨度与经过时间不一致");
        }
        if (("SPECIAL".equals(scope) || "TELEPORT".equals(method))
                && (intent == null || intent.usedAbilityIds() == null
                || !intent.usedAbilityIds().contains(transition.abilityId()))) {
            throw new LocationValidationException("LOCATION_ABILITY_INVALID", "特殊迁移没有可验证的能力来源");
        }
        String destinationId = state.knownLocations.stream()
                .filter(java.util.Objects::nonNull)
                .filter(location -> finalName.equals(location.name))
                .map(location -> location.id)
                .filter(LocationAuthority::usable)
                .findFirst()
                .orElseGet(() -> idFor(finalName));
        return new AgentResult.LocationTransition(state.currentLocationId, destinationId, finalName, scope,
                method, transition.abilityId(), transition.reason().strip());
    }

    public static boolean requiresTransitionRepair(
            AgentResult.LocationTransition transition, GameState state, String destination, ActionIntent intent) {
        repairLegacyState(state);
        String proposedName = usable(destination) ? destination.strip() : state.currentLocation;
        return transition == null && !proposedName.equals(state.currentLocation) && requiresMovementEvidence(intent);
    }

    public static String idFor(String name) {
        return "location-" + UUID.nameUUIDFromBytes(("wenxian:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public static int absoluteTime(GameState state) {
        long value = (long) state.worldYear * 360L * 1440L
                + (long) Math.max(0, state.day - 1) * 1440L + state.minute;
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    private static boolean usable(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean requiresMovementEvidence(ActionIntent intent) {
        return intent != null && ("TRAVEL".equals(intent.actionType()) || "ESCAPE".equals(intent.actionType()));
    }

    public static final class LocationValidationException extends IllegalArgumentException {
        private final String code;

        public LocationValidationException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

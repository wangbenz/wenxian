package com.wenxian.game.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.config.RuntimeAiConfigService;
import com.wenxian.game.model.ApiModels.TurnRequest;
import com.wenxian.game.model.GameState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ActionIntentAgentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ActionIntentAgentService.class);
    private static final Set<String> ACTION_TYPES = Set.of(
            "COMBAT", "ESCAPE", "INFILTRATE", "NEGOTIATE", "CULTIVATE", "EXPLORE",
            "INVESTIGATE", "TRAVEL", "USE_ITEM", "USE_ABILITY", "REST", "SELF_DESTRUCTIVE", "OTHER"
    );
    private static final Set<String> RISKS = Set.of("LOW", "MEDIUM", "HIGH", "LETHAL");
    private static final Set<String> INTENT_MODES = Set.of(
            "EXECUTE", "ATTEMPT", "SEEK", "LEARN", "DECLARE_OUTCOME", "ASK"
    );
    private static final Set<String> CLAIM_USAGES = Set.of(
            "REQUIRED_EXISTING", "TARGET", "DECLARED_RESULT", "CONTEXT"
    );
    private final ObjectMapper mapper;
    private final WorldGenerationService model;

    public ActionIntentAgentService(ObjectMapper mapper, WorldGenerationService model) {
        this.mapper = mapper;
        this.model = model;
    }

    public ActionIntent interpret(TurnRequest request, String action, GameState state,
                                  RuntimeAiConfigService.RuntimeConfig config, String credentialSource) {
        ActionIntent structured = structuredIntent(request, state);
        if (structured != null) return attachDuration(structured, action, state);
        try {
            String system = """
                    你是《问仙录》的行动语义解析器，不负责判定成败，也不写剧情。只输出JSON。
                    Java提供的状态是唯一事实。玩家行动中声称的新境界、人物身份、物品、能力都只能放入claims，不能当作事实。
                    intentMode必须区分：EXECUTE直接使用已有事物；ATTEMPT尝试但不保证成功；SEEK寻找尚未拥有的目标；LEARN参悟或学习；DECLARE_OUTCOME直接宣称已经获得、发现或完成；ASK询问或打听。
                    玩家说“寻找某物”时，该物是TARGET，不要求已在行囊。玩家说“已经找到/获得某物”时是DECLARED_RESULT。玩家直接使用某物或能力时才是REQUIRED_EXISTING。
                    “若途中无危险就继续抵达、若遇阻则中止、尽量完成”等条件式计划仍是ATTEMPT，不是DECLARE_OUTCOME；只有玩家把尚未发生的成功、获得或发现直接写成既成事实时才使用DECLARE_OUTCOME。
                    若未掌握某功法，但行囊中存在同名玉简、秘籍或传承载体，且玩家表达尝试、参悟或学习，应判为LEARN或ATTEMPT：载体是REQUIRED_EXISTING，待学习能力是TARGET，不能误判为直接施展。
                    breakthroughAttempt只有在玩家明确要冲击当前境界的下一大境界时才为true。日常修炼、请教功法、谈论他人境界、获得感悟或稳固修为都必须为false。
                    targetCharacterId只能引用已知人物ID；找不到时留空。targetRealm只能来自已知人物状态或当前场景正文，不能来自本轮玩家自述。
                    actionType只能是COMBAT、ESCAPE、INFILTRATE、NEGOTIATE、CULTIVATE、EXPLORE、INVESTIGATE、TRAVEL、USE_ITEM、USE_ABILITY、REST、SELF_DESTRUCTIVE、OTHER。
                    riskLevel只能是LOW、MEDIUM、HIGH、LETHAL。只有玩家明确表示接受死亡或采取显然自毁行为时acceptsLethalRisk才为true。
                    JSON格式：
                    {"actionType":"OTHER","intentMode":"ATTEMPT|EXECUTE|SEEK|LEARN|DECLARE_OUTCOME|ASK","breakthroughAttempt":false,"targetCharacterId":null,"targetName":null,"targetRealm":null,"targetRealmSource":"UNKNOWN|CHARACTER_STATE|CURRENT_SCENE|USER_CLAIM","usedAbilityIds":[],"riskLevel":"MEDIUM","acceptsLethalRisk":false,
                     "claims":[{"subjectType":"PLAYER|CHARACTER|ITEM|ABILITY|BACKGROUND","subjectId":"","field":"","value":"","requiredForAction":true,"usage":"REQUIRED_EXISTING|TARGET|DECLARED_RESULT|CONTEXT"}]}
                    """;
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("player", authoritativePlayer(state.player));
            context.put("sceneTitle", state.sceneTitle);
            context.put("currentLocation", state.currentLocation);
            context.put("currentNarrative", state.narrative.stream().limit(3).toList());
            // Intent parsing only needs stable identity and availability. Long descriptions
            // belong to the prose prompt after Java has resolved the referenced entities.
            context.put("characters", state.characters.stream().map(value -> compact(
                    "id", value.id, "name", value.name, "identity", value.identity,
                    "realm", value.realm, "location", value.location)).toList());
            context.put("inventory", state.inventory.stream().map(value -> compact(
                    "id", value.id, "definitionId", value.definitionId,
                    "name", value.name, "quantity", value.quantity)).toList());
            context.put("abilities", state.abilities.stream().map(value -> compact(
                    "id", value.id, "definitionId", value.definitionId, "name", value.name,
                    "type", value.type, "stage", value.stage,
                    "remainingUses", value.remainingUses, "maxUses", value.maxUses)).toList());
            context.put("playerAction", action);
            WorldGenerationService.ChatResponse response = model.requestMessage(
                    List.of(Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", mapper.writeValueAsString(context))),
                    List.of(), 450, 0.05, config, "ACTION_INTERPRETATION", state.saveId,
                    state.turn + 1, credentialSource);
            JsonNode message = response.message();
            IntentDraft draft = mapper.readValue(jsonObject(message.path("content").asText("{}")), IntentDraft.class);
            return attachDuration(sanitize(draft, state), action, state);
        } catch (Exception error) {
            LOGGER.warn("行动语义解析失败，关闭本轮推进: {}", error.getMessage());
            throw new ActionInterpretationException("无法可靠理解本轮行动", error);
        }
    }

    private ActionIntent attachDuration(ActionIntent intent, String action, GameState state) {
        Integer requestedMinutes = ActionDurationParser.parseMinutes(action, state == null ? 0 : state.minute);
        return requestedMinutes == null ? intent : intent.withRequestedDuration(requestedMinutes);
    }

    private ActionIntent structuredIntent(TurnRequest request, GameState state) {
        if ("CHOICE".equals(request.inputType())) {
            GameState.StoryChoice choice = state.choices.stream()
                    .filter(value -> value.id.equals(request.choiceId())).findFirst().orElse(null);
            if (choice == null || choice.actionType == null || choice.actionType.isBlank()
                    || "OTHER".equals(choice.actionType)) return null;
            // Cultivation choices still need semantic interpretation so ordinary practice and a
            // deliberate major-realm breakthrough cannot be confused with each other.
            if ("CULTIVATE".equals(choice.actionType)) return null;
            return new ActionIntent(normalizeActionType(choice.actionType), "ATTEMPT", false,
                    choice.targetCharacterId, null,
                    normalizeRealm(choice.targetRealm), choice.targetRealm == null ? "UNKNOWN" : "CURRENT_SCENE",
                    List.of(), normalizeRisk(choice.riskLevel), "LETHAL".equals(choice.riskLevel), List.of());
        }
        if ("USE_ITEM".equals(request.inputType())) {
            return new ActionIntent("USE_ITEM", "EXECUTE", false, null, null, null, "UNKNOWN", List.of(),
                    "LOW", false, List.of());
        }
        if ("USE_ABILITY".equals(request.inputType())) {
            return new ActionIntent("USE_ABILITY", "EXECUTE", false, null, null, null, "UNKNOWN",
                    request.targetId() == null ? List.of() : List.of(request.targetId()),
                    "LOW", false, List.of());
        }
        if ("PRACTICE".equals(request.inputType())) {
            return new ActionIntent("CULTIVATE", "ATTEMPT", false, null, null, null, "UNKNOWN",
                    request.targetId() == null ? List.of() : List.of(request.targetId()),
                    "LOW", false, List.of());
        }
        return null;
    }

    private ActionIntent sanitize(IntentDraft draft, GameState state) {
        if (draft == null) return new ActionIntent("OTHER", "ATTEMPT", false, null, null, null, "UNKNOWN",
                List.of(), "MEDIUM", false, List.of());
        String targetId = blankToNull(draft.targetCharacterId);
        final String requestedTargetId = targetId;
        GameState.CharacterRecord target = requestedTargetId == null ? null : state.characters.stream()
                .filter(value -> requestedTargetId.equals(value.id)).findFirst().orElse(null);
        if (target == null && draft.targetName != null) {
            target = state.characters.stream().filter(value -> draft.targetName.strip().equals(value.name))
                    .findFirst().orElse(null);
            if (target != null) targetId = target.id;
        }
        String targetRealm = null;
        String realmSource = "UNKNOWN";
        if (target != null && normalizeRealm(target.realm) != null) {
            targetRealm = normalizeRealm(target.realm);
            realmSource = "CHARACTER_STATE";
        } else if (sceneSupportsTargetRealm(draft.targetName, normalizeRealm(draft.targetRealm), state.narrative)) {
            targetRealm = normalizeRealm(draft.targetRealm);
            realmSource = "CURRENT_SCENE";
        } else if (normalizeRealm(draft.targetRealm) != null) {
            realmSource = "USER_CLAIM";
        }
        List<String> abilities = draft.usedAbilityIds == null ? List.of() : draft.usedAbilityIds.stream()
                .filter(value -> value != null && !value.isBlank()).distinct().limit(6).toList();
        List<ActionIntent.ClaimedFact> claims = new ArrayList<>();
        if (draft.claims != null) for (ClaimDraft claim : draft.claims) {
            if (claim == null || claim.field == null || claim.field.isBlank() || claim.value == null) continue;
            String usage = normalizeClaimUsage(claim.usage, claim.requiredForAction);
            claims.add(new ActionIntent.ClaimedFact(value(claim.subjectType, "BACKGROUND"),
                    value(claim.subjectId, ""), claim.field.strip(), claim.value.strip(),
                    "REQUIRED_EXISTING".equals(usage), usage));
            if (claims.size() == 8) break;
        }
        return new ActionIntent(normalizeActionType(draft.actionType), normalizeIntentMode(draft.intentMode),
                draft.breakthroughAttempt,
                targetId, blankToNull(draft.targetName),
                targetRealm, realmSource, abilities, normalizeRisk(draft.riskLevel),
                draft.acceptsLethalRisk, claims);
    }

    private String normalizeActionType(String value) {
        String normalized = value(value, "OTHER").toUpperCase();
        return ACTION_TYPES.contains(normalized) ? normalized : "OTHER";
    }

    private String normalizeIntentMode(String value) {
        String normalized = value(value, "ATTEMPT").toUpperCase();
        return INTENT_MODES.contains(normalized) ? normalized : "ATTEMPT";
    }

    private String normalizeClaimUsage(String value, boolean requiredForAction) {
        String normalized = value(value, requiredForAction ? "REQUIRED_EXISTING" : "CONTEXT").toUpperCase();
        return CLAIM_USAGES.contains(normalized) ? normalized
                : requiredForAction ? "REQUIRED_EXISTING" : "CONTEXT";
    }

    private String normalizeRisk(String value) {
        String normalized = value(value, "MEDIUM").toUpperCase();
        return RISKS.contains(normalized) ? normalized : "MEDIUM";
    }

    private String normalizeRealm(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.contains("练气") || value.contains("炼气")) return "引气";
        return List.of("凡俗", "引气", "筑基", "金丹", "元婴", "化神", "炼虚", "合道")
                .stream().filter(value::contains).findFirst().orElse(null);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate.strip();
    }

    private boolean sceneSupportsTargetRealm(String targetName, String realm, List<String> narrative) {
        if (targetName == null || targetName.isBlank() || realm == null || narrative == null) return false;
        String name = targetName.strip();
        return narrative.stream().anyMatch(paragraph -> paragraph != null
                && paragraph.contains(name) && paragraph.contains(realm));
    }

    private String jsonObject(String content) {
        if (content == null) return "{}";
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start >= 0 && end > start ? content.substring(start, end + 1) : content;
    }

    private Map<String, Object> authoritativePlayer(GameState.PlayerState player) {
        if (player == null) return Map.of();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", player.name);
        value.put("race", player.race);
        value.put("gender", player.gender);
        value.put("age", player.age);
        value.put("identity", player.identity);
        value.put("origin", player.origin);
        value.put("trait", player.trait);
        value.put("originPremise", player.originPremise);
        value.put("realm", player.realm);
        value.put("constitution", player.constitution);
        value.put("condition", player.condition);
        value.put("goal", player.goal);
        value.put("burden", player.burden);
        return value;
    }

    private Map<String, Object> compact(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            Object value = values[index + 1];
            if (value != null) result.put(String.valueOf(values[index]), value);
        }
        return result;
    }

    private static class IntentDraft {
        public String actionType;
        public String intentMode;
        public boolean breakthroughAttempt;
        public String targetCharacterId;
        public String targetName;
        public String targetRealm;
        public String targetRealmSource;
        public List<String> usedAbilityIds;
        public String riskLevel;
        public boolean acceptsLethalRisk;
        public List<ClaimDraft> claims;
    }

    private static class ClaimDraft {
        public String subjectType;
        public String subjectId;
        public String field;
        public String value;
        public boolean requiredForAction;
        public String usage;
    }
}

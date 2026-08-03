package com.wenxian.v2.ai.application;

import com.wenxian.v2.ai.domain.ActionAdjudication;
import com.wenxian.v2.ai.domain.ActionIntent;
import com.wenxian.v2.ai.domain.IntentContext;
import com.wenxian.v2.game.domain.Race;
import com.wenxian.v2.game.domain.RealmLadder;
import com.wenxian.v2.game.domain.WorldCalendar;
import com.wenxian.v2.game.persistence.LocationRepository;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
public class ActionAdjudicator {
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "WAIT", "TRAVEL", "INVESTIGATE", "INTERACT", "USE_ITEM", "USE_ABILITY",
            "CULTIVATE", "TRADE", "COMBAT", "OTHER");
    private static final long MAX_WAIT_MINUTES = 100L * WorldCalendar.MINUTES_PER_YEAR;
    private static final Set<String> OPEN_WORLD_DISCOVERY_TYPES = Set.of("INVESTIGATE", "INTERACT");

    private final LocationRepository locations;

    public ActionAdjudicator(LocationRepository locations) {
        this.locations = locations;
    }

    public ActionAdjudication adjudicate(IntentContext context, ActionIntent intent) {
        // Investigation and conversation may legitimately discover an incidental person or detail
        // that has not been registered yet. Reference-bound actions remain strict below.
        if (!intent.ambiguities().isEmpty() && !OPEN_WORLD_DISCOVERY_TYPES.contains(intent.actionType())) {
            return ActionAdjudication.clarify("这次行动还有无法唯一确认的对象或含义。",
                    "补充目标、地点或准备使用的事物");
        }
        if (!SUPPORTED_TYPES.contains(intent.actionType())) {
            return ActionAdjudication.clarify("暂时无法确认你想采取哪一类行动。", "换一种更明确的说法");
        }
        if (intent.declaresGuaranteedOutcome() || "DECLARE_OUTCOME".equals(intent.intentMode())) {
            return ActionAdjudication.reject("OUTCOME_CANNOT_BE_DECLARED",
                    "你可以尝试行动，但不能直接指定突破、胜利、获得或他人的反应。",
                    "把结果改写成一次尝试");
        }

        ActionAdjudication referenceCheck = validateReferences(context, intent);
        if (referenceCheck != null) return referenceCheck;

        return switch (intent.actionType()) {
            case "WAIT" -> validateWait(intent);
            case "TRAVEL" -> validateTravel(context, intent);
            case "USE_ITEM" -> intent.referencedItemIds().isEmpty()
                    ? ActionAdjudication.clarify("没有确认你准备使用哪件行囊物品。", "明确物品名称")
                    : ActionAdjudication.accept();
            case "USE_ABILITY" -> intent.referencedAbilityIds().isEmpty()
                    ? ActionAdjudication.clarify("没有确认你准备施展哪项已掌握能力。", "明确功法、天赋或技艺")
                    : ActionAdjudication.accept();
            case "CULTIVATE" -> validateCultivation(context, intent);
            default -> ActionAdjudication.accept();
        };
    }

    private ActionAdjudication validateReferences(IntentContext context, ActionIntent intent) {
        Set<String> knownItems = ids(context.itemCandidates());
        if (!knownItems.containsAll(intent.referencedItemIds())) {
            return ActionAdjudication.reject("ITEM_NOT_OWNED", "行动引用了当前行囊中不存在的物品。",
                    "从角色页确认物品后重新描述");
        }
        Set<String> knownAbilities = ids(context.abilityCandidates());
        if (!knownAbilities.containsAll(intent.referencedAbilityIds())) {
            return ActionAdjudication.reject("ABILITY_NOT_MASTERED", "行动引用了角色尚未掌握的能力。",
                    "使用角色页已经记录的天赋、功法或技艺");
        }
        if (intent.targetCharacterId() != null && !intent.targetCharacterId().isBlank()
                && !ids(context.characterCandidates()).contains(intent.targetCharacterId())) {
            return ActionAdjudication.reject("CHARACTER_REFERENCE_UNKNOWN", "行动对象不在本轮候选人物中。",
                    "先调查或接触该人物");
        }
        return null;
    }

    private ActionAdjudication validateWait(ActionIntent intent) {
        Long minutes = WorldCalendar.durationMinutes(intent.durationAmount(), intent.durationUnit(),
                intent.durationMinutes());
        if (minutes == null || minutes <= 0 || minutes > MAX_WAIT_MINUTES) {
            return ActionAdjudication.clarify("等待或闭关需要明确合理的持续时间。",
                    "例如休息一夜、等待三日或闭关一个月");
        }
        return ActionAdjudication.accept();
    }

    private ActionAdjudication validateTravel(IntentContext context, ActionIntent intent) {
        String target = intent.targetLocationId();
        if (target == null || target.isBlank()) {
            return ActionAdjudication.clarify("没有确认这次移动的目的地。", "明确要前往的已知地点");
        }
        if (!ids(context.locationCandidates()).contains(target)) {
            return ActionAdjudication.reject("LOCATION_UNKNOWN", "目的地尚未进入角色的已知世界。",
                    "先打听该地点或选择已知地点");
        }
        if (locations.directEdge(context.currentLocationId(), target) == null) {
            return ActionAdjudication.reject("TRAVEL_ROUTE_UNAVAILABLE", "当前位置与目的地之间没有直接路线。",
                    "先前往相邻地点");
        }
        return ActionAdjudication.accept();
    }

    private ActionAdjudication validateCultivation(IntentContext context, ActionIntent intent) {
        if (intent.targetRealmCode() == null || intent.targetRealmCode().isBlank()) {
            return ActionAdjudication.accept();
        }
        Race race = Race.valueOf(context.protagonist().race());
        if (!RealmLadder.isAdjacent(race, context.protagonist().realmCode(), intent.targetRealmCode())) {
            return ActionAdjudication.reject("REALM_TRANSITION_INVALID",
                    "目标境界不是当前种族修炼道路上的下一阶段。", "先完成当前阶段，再尝试相邻突破");
        }
        return ActionAdjudication.accept();
    }

    private Set<String> ids(java.util.List<IntentContext.EntityCandidate> candidates) {
        Set<String> result = new HashSet<>();
        candidates.forEach(candidate -> result.add(candidate.id()));
        return result;
    }
}

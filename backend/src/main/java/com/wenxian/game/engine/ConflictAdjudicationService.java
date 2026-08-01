package com.wenxian.game.engine;

import com.wenxian.game.config.RuntimeAiConfigService;
import com.wenxian.game.model.ApiModels.TurnRequest;
import com.wenxian.game.model.GameState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class ConflictAdjudicationService {
    private static final List<String> REALMS = List.of("凡俗", "引气", "筑基", "金丹", "元婴", "化神", "炼虚", "合道");
    private static final List<String> CONDITIONS = List.of("STABLE", "WOUNDED", "SEVERE", "DYING");
    private final ActionIntentAgentService intentAgent;

    public ConflictAdjudicationService(ActionIntentAgentService intentAgent) {
        this.intentAgent = intentAgent;
    }

    public ActionAdjudication adjudicate(TurnRequest request, String action, GameState state,
                                          RuntimeAiConfigService.RuntimeConfig config, String credentialSource) {
        ActionIntent intent;
        try {
            intent = intentAgent.interpret(request, action, state, config, credentialSource);
        } catch (ActionInterpretationException error) {
            return ActionAdjudication.clarified("ACTION_UNCLEAR", "此念尚未看清",
                    "命卷没有可靠理解这次行动，为避免错误改写人物和世界状态，本轮没有发生任何变化。",
                    List.of("补充要做什么以及行动对象", "从当前选项中选择一个方向", "使用更完整的一句话重新描述"));
        }
        AuthoritativeEntityResolver.IntentResolution entityResolution =
                AuthoritativeEntityResolver.resolveIntent(intent, action, state);
        if (entityResolution.issue() != null) {
            AuthoritativeEntityResolver.ResolutionIssue issue = entityResolution.issue();
            return ActionAdjudication.clarified(issue.code(), issue.title(), issue.reason(), issue.suggestions());
        }
        intent = entityResolution.intent();
        ActionAdjudication factFailure = validateFacts(intent, state);
        if (factFailure != null) return factFailure;
        return ActionAdjudication.accepted(intent, resolve(intent, state));
    }

    ActionAdjudication validateFacts(ActionIntent intent, GameState state) {
        for (String abilityId : intent.usedAbilityIds()) {
            GameState.AbilityRecord ability = state.abilities.stream()
                    .filter(value -> abilityId.equals(value.id)).findFirst().orElse(null);
            if (ability == null) {
                return ActionAdjudication.rejected(intent, "MISSING_ABILITY", "此法尚未掌握",
                        "当前命卷中没有记录你所施展的能力，不能把尚未获得的力量直接写成事实。",
                        List.of("查看已掌握的天赋与功法", "寻找该能力的传承", "改用当前已有手段"));
            }
            if (activelyUsesAbilities(intent) && ability.maxUses > 0 && ability.remainingUses <= 0) {
                return ActionAdjudication.rejected(intent, "ABILITY_USES_EXHAUSTED", "此法暂不可再施",
                        "这项能力确实属于你，但当前可用次数已经耗尽，需要依照其恢复条件等待或付出代价。",
                        List.of("查看能力的恢复条件", "改用其他已掌握手段", "先脱离险境休整"));
            }
        }
        for (ActionIntent.ClaimedFact claim : intent.claims()) {
            if (!"REQUIRED_EXISTING".equals(claim.usage()) && !claim.requiredForAction()) continue;
            String type = claim.subjectType() == null ? "BACKGROUND" : claim.subjectType().toUpperCase();
            if ("PLAYER".equals(type) && "realm".equalsIgnoreCase(claim.field())
                    && state.player != null && !realmEquals(state.player.realm, claim.value())) {
                return ActionAdjudication.rejected(intent, "REALM_CONFLICT", "境界并非如此",
                        "当前权威记录中你的境界是“" + state.player.realm + "”，不能在行动中自行改成“" + claim.value() + "”。",
                        List.of("依照当前境界采取行动", "寻找下一境界的突破条件", "先调查对手实力"));
            }
            if ("ITEM".equals(type) && state.inventory.stream()
                    .noneMatch(value -> safeEquals(claim.subjectId(), value.id))) {
                return ActionAdjudication.rejected(intent, "MISSING_ITEM", "此物并不在身",
                        "当前行囊中没有这件物品，不能直接取出或使用。",
                        List.of("查看当前行囊", "寻找这件物品", "改用已有物品"));
            }
            if ("ABILITY".equals(type) && state.abilities.stream()
                    .noneMatch(value -> safeEquals(claim.subjectId(), value.id))) {
                return ActionAdjudication.rejected(intent, "MISSING_ABILITY", "此法尚未入门",
                        "当前命卷中没有这项天赋或功法，不能直接施展。",
                        List.of("查看已掌握能力", "寻找对应传承", "使用已有功法应对"));
            }
            if ("CHARACTER".equals(type) && "realm".equalsIgnoreCase(claim.field())) {
                GameState.CharacterRecord character = state.characters.stream()
                        .filter(value -> safeEquals(claim.subjectId(), value.id)).findFirst().orElse(null);
                if (character == null || normalizeRealm(character.realm) == null) {
                    return ActionAdjudication.rejected(intent, "TARGET_REALM_UNKNOWN", "此人修为尚未证实",
                            "当前记录无法证明该人物拥有你所声称的境界，不能依赖这项未经确认的前提行动。",
                            List.of("先确认此人的真实修为", "不依赖该人物重新计划", "寻找其他可靠助力"));
                }
                if (!realmEquals(character.realm, claim.value())) {
                    return ActionAdjudication.rejected(intent, "TARGET_REALM_CONFLICT", "人物状态与记录不符",
                            character.name + "当前记录的境界是“" + character.realm + "”，并非“" + claim.value() + "”。",
                            List.of("依据其真实境界重新计划", "先与此人交谈", "寻找其他助力"));
                }
            }
        }
        boolean declaredOutcome = intent.claims().stream()
                .anyMatch(claim -> "DECLARED_RESULT".equals(claim.usage()));
        if ("DECLARE_OUTCOME".equals(intent.intentMode()) || declaredOutcome) {
            return ActionAdjudication.clarified(intent, "DECLARED_OUTCOME", "结果不能由行动直接决定",
                    "你可以决定接下来尝试什么，但发现、获得与成败仍要由当前世界状态和行动过程共同决定。",
                    outcomeSuggestions(intent));
        }
        return null;
    }

    private boolean activelyUsesAbilities(ActionIntent intent) {
        return intent != null && Set.of("EXECUTE", "ATTEMPT").contains(intent.intentMode())
                && Set.of("USE_ABILITY", "COMBAT", "ESCAPE", "INFILTRATE").contains(intent.actionType());
    }

    private List<String> outcomeSuggestions(ActionIntent intent) {
        return switch (intent.actionType()) {
            case "INVESTIGATE", "EXPLORE" -> List.of("循着现有线索继续寻找", "先观察周围留下的痕迹", "向知情者打听目标下落");
            case "CULTIVATE" -> List.of("尝试依照现有法门修炼", "先检查突破所需条件", "寻找能够辅助修炼的资源");
            default -> List.of("改为描述准备采取的行动", "从当前线索继续尝试", "先确认目标是否真实存在");
        };
    }

    ConflictVerdict resolve(ActionIntent intent, GameState state) {
        if ("SELF_DESTRUCTIVE".equals(intent.actionType()) && intent.acceptsLethalRisk()) {
            return new ConflictVerdict("DEAD", "SELF_DESTRUCTIVE", "DYING", true,
                    List.of("玩家明确选择了不可逆的自毁行动"),
                    List.of("必须如实写出死亡，不能临时出现无来源救援", "outcome必须为DEAD"));
        }
        if (!"COMBAT".equals(intent.actionType())) {
            if ("LETHAL".equals(intent.riskLevel())) {
                String condition = worsen(condition(state.player), "HARD".equals(state.difficulty) ? 2 : 1);
                return new ConflictVerdict("DANGEROUS_ACTION", "UNKNOWN", condition, false,
                        List.of("本轮行动具有明确致命风险"),
                        List.of("可以失败或受伤，但本轮不得无依据直接死亡", "后果必须与行动风险直接相关"));
            }
            return ConflictVerdict.narrativeOnly();
        }

        String playerRealm = normalizeRealm(state.player == null ? null : state.player.realm);
        String targetRealm = trustedTargetRealm(intent, state);
        if (playerRealm == null || targetRealm == null) {
            List<String> constraints = new ArrayList<>();
            constraints.add("敌我境界缺少权威记录，本轮不得凭空宣称任何一方存在确定的境界碾压");
            constraints.add("可以通过试探、交锋、撤退或暴露新情报推进，但不得擅自改写主角境界");
            if ("USER_CLAIM".equals(intent.targetRealmSource())) {
                constraints.add("玩家在本轮输入中声称的敌人境界不是权威事实");
            }
            return new ConflictVerdict("UNRESOLVED_CONFLICT", "UNKNOWN", condition(state.player), false,
                    List.of("主角境界：" + value(playerRealm, "未知"), "对手境界：尚未确认"), constraints);
        }

        GameState.CharacterRecord target = intent.targetCharacterId() == null ? null : state.characters.stream()
                .filter(value -> intent.targetCharacterId().equals(value.id)).findFirst().orElse(null);
        int score = (REALMS.indexOf(playerRealm) - REALMS.indexOf(targetRealm)) * 3;
        score -= conditionPenalty(condition(state.player));
        if (target != null) score += conditionPenalty(condition(target.condition));
        score += Math.min(2, intent.usedAbilityIds().size());
        if ("HARD".equals(state.difficulty) && score < 4) score -= 1;

        String currentCondition = condition(state.player);
        String verdict;
        String relation;
        String after = currentCondition;
        List<String> constraints = new ArrayList<>();
        if (score >= 6) {
            verdict = "DOMINANT_SUCCESS";
            relation = "OVERWHELMING_ADVANTAGE";
            constraints.add("主角至少高出对手两个大境界，正面交锋不得写成被对手击败或重创");
            constraints.add("对手只能凭阵法、人数、情报或拖延制造有限麻烦，不能跨越境界差硬压主角");
        } else if (score >= 3) {
            verdict = "SUCCESS";
            relation = "ADVANTAGE";
            constraints.add("主角占有明确实力优势，结果应以成功为主；代价必须有具体外部来源");
        } else if (score >= 1) {
            verdict = "SUCCESS_WITH_COST";
            relation = "SLIGHT_ADVANTAGE";
            if (Set.of("HIGH", "LETHAL").contains(intent.riskLevel())) after = worsen(currentCondition, 1);
            constraints.add("主角可以取胜，但允许付出与风险相称的伤势、暴露或资源代价");
        } else if (score >= -1) {
            verdict = "CONTESTED";
            relation = "EVEN";
            if (Set.of("HIGH", "LETHAL").contains(intent.riskLevel())) after = worsen(currentCondition, 1);
            constraints.add("双方实力接近，不得无铺垫秒杀；胜负应由准备、克制和环境决定");
        } else if (score >= -4) {
            verdict = "FAILURE_WITH_ESCAPE";
            relation = "DISADVANTAGE";
            after = worsen(currentCondition, 1);
            constraints.add("主角处于劣势，不能正面击败对手；可通过代价、情报或环境争取脱身");
        } else {
            verdict = "OVERMATCHED";
            relation = "OVERWHELMING_DISADVANTAGE";
            after = worsen(currentCondition, "HARD".equals(state.difficulty) ? 2 : 1);
            constraints.add("对手至少高出主角两个大境界，主角不得正面取胜或无代价全身而退");
            constraints.add("应表现为失败、重伤、被擒或付出重大代价后脱身");
        }
        boolean death = "OVERMATCHED".equals(verdict) && "DYING".equals(currentCondition)
                && intent.acceptsLethalRisk() && "LETHAL".equals(intent.riskLevel());
        if (death) {
            verdict = "DEAD";
            after = "DYING";
            constraints.add("主角已濒死且明确接受致命风险，本轮必须判定死亡");
        } else {
            constraints.add("outcome必须为CONTINUE");
        }
        return new ConflictVerdict(verdict, relation, after, death,
                List.of("主角境界：" + playerRealm, "对手境界：" + targetRealm,
                        "主角身体：" + currentCondition, "难度：" + state.difficulty), constraints);
    }

    public boolean repairLegacyState(GameState state) {
        boolean changed = false;
        if (state.player != null && (state.player.condition == null || !CONDITIONS.contains(state.player.condition))) {
            // Old numeric vitality is not trusted because the prose Agent often returned deltas as absolutes.
            state.player.condition = state.player.vitality <= 0 ? "DYING" : "STABLE";
            if (state.player.vitality > 0 && state.player.vitality < 30) state.player.vitality = 60;
            changed = true;
        }
        if (state.player != null && !"dead".equals(state.phase) && "STABLE".equals(state.player.condition)
                && state.player.vitality < 30) {
            state.player.vitality = 60;
            changed = true;
        }
        for (GameState.CharacterRecord character : state.characters) {
            if (character.realm == null || character.realm.isBlank()) {
                character.realm = "UNKNOWN";
                changed = true;
            }
            if (character.condition == null || !CONDITIONS.contains(character.condition)) {
                character.condition = "STABLE";
                changed = true;
            }
            if (character.combatProfile == null || character.combatProfile.isBlank()) {
                character.combatProfile = "尚未确认";
                changed = true;
            }
        }
        return changed;
    }

    private String trustedTargetRealm(ActionIntent intent, GameState state) {
        if (intent.targetCharacterId() != null) {
            GameState.CharacterRecord target = state.characters.stream()
                    .filter(value -> intent.targetCharacterId().equals(value.id)).findFirst().orElse(null);
            String realm = normalizeRealm(target == null ? null : target.realm);
            if (realm != null) return realm;
        }
        return intent.targetRealmSource() != null
                && Set.of("CURRENT_SCENE", "CHARACTER_STATE").contains(intent.targetRealmSource())
                ? normalizeRealm(intent.targetRealm()) : null;
    }

    private boolean realmEquals(String left, String right) {
        String a = normalizeRealm(left);
        String b = normalizeRealm(right);
        return a != null && a.equals(b);
    }

    private String normalizeRealm(String realm) {
        if (realm == null || realm.isBlank() || "UNKNOWN".equals(realm)) return null;
        if (realm.contains("练气") || realm.contains("炼气")) return "引气";
        return REALMS.stream().filter(realm::contains).findFirst().orElse(null);
    }

    private String condition(GameState.PlayerState player) {
        return player == null ? "STABLE" : condition(player.condition);
    }

    private String condition(String value) {
        return CONDITIONS.contains(value) ? value : "STABLE";
    }

    private int conditionPenalty(String condition) {
        return switch (condition(condition)) {
            case "WOUNDED" -> 1;
            case "SEVERE" -> 2;
            case "DYING" -> 4;
            default -> 0;
        };
    }

    private String worsen(String current, int steps) {
        int index = CONDITIONS.indexOf(condition(current));
        return CONDITIONS.get(Math.min(CONDITIONS.size() - 1, index + Math.max(0, steps)));
    }

    private String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    private boolean safeEquals(String left, String right) {
        return left != null && left.equals(right);
    }
}

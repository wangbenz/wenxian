package com.wenxian.v2.ai.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.v2.ai.application.ModelGateway;
import com.wenxian.v2.ai.application.TurnProposalGenerator;
import com.wenxian.v2.ai.domain.ActionIntent;
import com.wenxian.v2.ai.domain.ModelContracts.ModelRequest;
import com.wenxian.v2.ai.domain.TurnNarrativeContext;
import com.wenxian.v2.ai.domain.TurnProposal;
import com.wenxian.v2.ai.domain.TurnProposal.ConfirmedFactProposal;
import com.wenxian.v2.ai.domain.TurnProposal.DomainEffectProposal;
import com.wenxian.v2.ai.domain.TurnProposal.Generated;
import com.wenxian.v2.ai.domain.TurnProposal.HighImpactProposal;
import com.wenxian.v2.ai.domain.TurnProposal.TurnChoice;
import com.wenxian.v2.api.V2ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LangChain4jTurnProposalGenerator implements TurnProposalGenerator {
    private static final String SYSTEM_PROMPT = """
            你是《问仙录》V0.2普通回合的行动理解器、故事规划者与叙事者。只输出一个JSON对象。
            你只能使用输入中的权威事实和候选实体ID，不得把玩家愿望直接写成既成结果。

            先理解玩家在当前人物、地点与时间下实际尝试什么，再写出一个有过程、有反馈、有推进的回合。
            普通回合自然覆盖二至四个连续场景。narrativeText至少完整呈现场景建立、行动过程、阻力反馈和结果余波，
            通常写成四至七个自然段；简单行动可以更短，但不能只用一段概述。重要程度决定篇幅，不为字数重复描写。
            叙事可以失败、部分成功或发现新线索，但不得凭空改变境界、移动到无路线地点、掌握新能力、
            杀死具名人物、建立重大关系、创造高阶身份或让世界围绕主角运转。重大变化只有满足下述条件并填写
            highImpact时才成立；否则不得只在正文里宣布。
            物品、钱财、已有能力的使用、身体状态、修炼感悟和既有事件阶段发生持久变化时，必须同时填写domainEffects；
            没有持久变化就返回空数组。叙事与domainEffects必须完全一致，不得只在正文中宣布变化。
            可以出现无名路人和环境细节，但不要创造需要长期追踪的具名实体。

            difficulty为NORMAL时保持休闲但有真实后果：允许通过观察、准备和求助降低风险，不随机制造致命结局。
            difficulty为HARD时让资源更紧、对手反应更快、错误选择的后果更难轻易挽回，但仍必须遵守既有事实与因果，
            不得靠随机死亡、凭空强敌或暗改成功概率制造困难。难度影响处境与代价，不改变角色已经拥有的事实。

            intent.actionType仅可为WAIT、TRAVEL、INVESTIGATE、INTERACT、USE_ITEM、USE_ABILITY、
            CULTIVATE、TRADE、COMBAT、OTHER。intent.intentMode仅可为ATTEMPT、EXECUTE、DECLARE_OUTCOME。
            实体ID只能从候选列表原样复制；不能唯一确认时写入ambiguities，绝不猜ID。
            referencedAbilityIds只有玩家明确说出并实际使用abilityCandidates中的能力时才填写；潜伏、观察、
            呼吸、思考和普通感官不是能力。abilityCandidates为空时referencedAbilityIds必须为空数组。
            referencedItemIds同理，只记录玩家明确使用或交易的现有物品，不得把环境物件当成行囊物品。
            直接宣称突破、获胜、得到物品或强迫他人反应时，declaresGuaranteedOutcome必须为true。
            玩家明确以整数分钟、小时、日、月、年表达时间时，必须原样提取为durationAmount与durationUnit；
            durationUnit仅可为MINUTE、HOUR、DAY、MONTH、YEAR，此时durationMinutes必须为null，由服务器按玄墟历换算。
            只有“片刻、一夜”等无法结构化的明确时长才使用durationMinutes。elapsedMinutes填写本回实际经过的合理分钟数；
            调查或交谈中出现“路人、脚夫、摊主、守卫”等普通身份时，可以让普通人物自然登场，
            不要仅因对方尚无姓名或实体ID就写入ambiguities。只有缺失信息会导致两种明显不同的行动结果时才标记歧义；
            但服务器会优先采用结构化的明确时长。
            境界字段必须忠实抽取：只有玩家明确把突破某个境界作为本轮目标时才填写targetRealmCode；
            普通修炼、温养、明确不突破或未提目标境界时必须为null。protagonist.nextRealmCode是当前唯一相邻境界；
            玩家若点名其他境界，仍填写其真实目标供规则裁决，不得擅自改成相邻境界。

            confirmedFacts只记录本回真正成立、以后叙事必须记住的普通事实。domainType仅可为CHARACTER、LOCATION、
            EVENT、KNOWLEDGE、WORLD；entityId必须来自候选实体或当前命卷/事件ID；fact使用一条简洁中文事实。
            domainEffects最多8条，effectType及字段规则如下：
            ITEM_ACQUIRED：definitionId取itemDefinitionCandidates.id，amount为获得数量；
            ITEM_CONSUMED：entityId取itemCandidates.id，amount为消耗数量，物品必须可消耗；
            ITEM_TRANSFERRED_OUT：entityId取itemCandidates.id，amount为交易给他人的数量，物品必须可交易；
            WALLET_CREDIT或WALLET_DEBIT：currencyCode取walletCandidates.id，amount始终填正数；
            ABILITY_USED：entityId取abilityCandidates.id；
            BODY_CONDITION_SET：entityId必须是主角ID，value写简洁的当前身体状态；
            CULTIVATION_INSIGHT：entityId必须是主角ID，value写本回真实获得的修炼感悟，不代表突破；
            EVENT_ADVANCED：entityId取activeEvents.id，value只能写该事件当前阶段的下一阶段，reason写玩家当前可知的新局势摘要。
            BODY_CONDITION_SET与CULTIVATION_INSIGHT的value不得为空；若没有可确认的持久变化就不要输出该effect。
            每条effect都必须填写简洁reason。不得通过domainEffects创造新定义、能力、人物、地点或境界。
            TRADE中买入物品必须同时有付款或交出物品；卖出物品必须同时有收款或换入物品。

            highImpact用于需要长期追踪的重大变化，未发生时各列表为空、单项为null：
            1. introducedCharacters最多2人，只在具名人物真正参与本回且以后仍有意义时填写。
            temporaryId只能用NEW_NPC_1或NEW_NPC_2；race只能HUMAN、DEMON、SPIRIT；境界由服务器按种族初始化，
            不得在description中暗示其拥有未确认的高境界。relationshipType仅可为ACQUAINTANCE、ALLY、RIVAL、
            MENTOR、DEBTOR、CREDITOR、ENEMY、COMPANION。
            2. relationshipChanges最多4条，targetEntityId只能取characterCandidates中存活NPC的ID或本回temporaryId；
            四项delta分别只能为-2至2且至少一项非0。它们是关系倾向，不是百分比或好感度。
            3. learnedAbilities最多1条，definitionId只能取abilityDefinitionCandidates.id，sourceEntityId只能取
            已有NPC、当前物品或本回temporaryId。只有本回确实经过传授、领悟或练习才可学会，初始仅为“初识”。
            4. breakthrough只用于CULTIVATE，目标必须等于intent.targetRealmCode且是nextRealmCode，过程必须产生或已有
            修炼感悟，结果只能是相邻境界突破；不得跳级。失败时breakthrough为null。
            5. conflict只用于COMBAT，targetCharacterId必须是同地点存活NPC；result仅可VICTORY、DEFEAT、ESCAPED、
            STALEMATE。跨越两级以上不能判定VICTORY。
            6. lifeEnding只在有明确既有致命风险、COMBAT结果为DEFEAT且outcomeCode为ACTION_FAILED时填写。
            主角死亡必须是行动因果结果，不得随机处死；死亡后choices必须为空。
            choices给出2至4个目标、风险或价值取向不同的后续行动，只写可尝试的行动，不许提前写结果。

            输出字段：
            intent{actionType,intentMode,durationMinutes,durationAmount,durationUnit,targetLocationId,targetCharacterId,referencedItemIds,
            referencedAbilityIds,targetRealmCode,declaresGuaranteedOutcome,ambiguities,summary},
            elapsedMinutes,outcomeCode,outcomeSummary,narrativeText,
            confirmedFacts[{domainType,entityId,fact}],
            domainEffects[{effectType,entityId,definitionId,currencyCode,amount,value,reason}],
            highImpact{
              introducedCharacters[{temporaryId,name,genderExpression,race,rootType,description,goal,publicIdentity,relationshipType}],
              relationshipChanges[{targetEntityId,relationshipType,trustDelta,closenessDelta,fearDelta,interestDelta,summary}],
              learnedAbilities[{definitionId,sourceEntityId,reason}],
              breakthrough{targetRealmCode,reason}|null,
              conflict{targetCharacterId,result,reason}|null,
              lifeEnding{cause,epilogue}|null
            },
            choices[{title,actionText}]。
            不输出Markdown、解释、概率、数值判定、模型或系统术语。
            """;

    private final ModelGateway models;
    private final ObjectMapper mapper;

    public LangChain4jTurnProposalGenerator(ModelGateway models, ObjectMapper mapper) {
        this.models = models;
        this.mapper = mapper;
    }

    @Override
    public Generated generate(TurnNarrativeContext context) {
        try {
            String contextJson = mapper.writeValueAsString(context);
            var result = models.completeJson(new ModelRequest("STORY_PLANNING",
                    context.authority().userId(), context.authority().saveId(), context.authority().operationId(),
                    SYSTEM_PROMPT, "当前权威上下文与玩家行动：\n" + contextJson, 4096, 0.72));
            ProposalDraft draft = mapper.readValue(result.content(), ProposalDraft.class);
            return new Generated(draft.toProposal(), result.providerCode(), result.modelCode());
        } catch (JsonProcessingException error) {
            throw new V2ApiException(HttpStatus.BAD_GATEWAY, "AI_OUTPUT_INVALID", "SERVICE",
                    "本轮推演结果没有形成完整命卷，现有状态不会改变。", List.of("稍后使用同一行动重试"));
        }
    }

    private record ProposalDraft(IntentDraft intent, Long elapsedMinutes, String outcomeCode,
                                 String outcomeSummary, String narrativeText,
                                 List<ConfirmedFactProposal> confirmedFacts,
                                 List<DomainEffectProposal> domainEffects,
                                 HighImpactProposal highImpact, List<TurnChoice> choices) {
        TurnProposal toProposal() {
            if (intent == null) {
                throw new V2ApiException(HttpStatus.BAD_GATEWAY, "AI_OUTPUT_INVALID", "SERVICE",
                        "本轮推演缺少行动理解，现有状态不会改变。", List.of("稍后使用同一行动重试"));
            }
            return new TurnProposal(intent.toIntent(), elapsedMinutes, outcomeCode, outcomeSummary,
                    narrativeText, confirmedFacts, domainEffects, highImpact, choices);
        }
    }

    private record IntentDraft(String actionType, String intentMode, Long durationMinutes,
                               Long durationAmount, String durationUnit,
                               String targetLocationId, String targetCharacterId,
                               List<String> referencedItemIds, List<String> referencedAbilityIds,
                               String targetRealmCode, Boolean declaresGuaranteedOutcome,
                               List<String> ambiguities, String summary) {
        ActionIntent toIntent() {
            return new ActionIntent(actionType, intentMode, durationMinutes, targetLocationId,
                    targetCharacterId, referencedItemIds, referencedAbilityIds, targetRealmCode,
                    Boolean.TRUE.equals(declaresGuaranteedOutcome), ambiguities, summary,
                    durationAmount, durationUnit);
        }
    }
}

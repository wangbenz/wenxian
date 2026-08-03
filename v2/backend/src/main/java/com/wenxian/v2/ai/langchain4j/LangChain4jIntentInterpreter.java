package com.wenxian.v2.ai.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.v2.ai.application.IntentInterpreter;
import com.wenxian.v2.ai.application.ModelGateway;
import com.wenxian.v2.ai.domain.ActionIntent;
import com.wenxian.v2.ai.domain.IntentContext;
import com.wenxian.v2.ai.domain.ModelContracts.ModelRequest;
import com.wenxian.v2.api.V2ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LangChain4jIntentInterpreter implements IntentInterpreter {
    private static final String SYSTEM_PROMPT = """
            你是《问仙录》的行动理解器，不负责写剧情，也不能决定行动成功。
            只把玩家输入转换为JSON。实体ID只能从候选列表复制；不存在或无法唯一确认时不要猜测，写入ambiguities。
            actionType仅可为WAIT、TRAVEL、INVESTIGATE、INTERACT、USE_ITEM、USE_ABILITY、CULTIVATE、TRADE、COMBAT、OTHER。
            intentMode仅可为ATTEMPT、EXECUTE、DECLARE_OUTCOME。玩家直接宣称突破、获胜、得到物品或强迫他人反应时，
            declaresGuaranteedOutcome必须为true。明确以整数分钟、小时、日、月、年表达的时间，原样写入durationAmount，
            durationUnit仅可为MINUTE、HOUR、DAY、MONTH、YEAR，并令durationMinutes为null；其他明确时长才使用durationMinutes。
            输出字段：actionType,intentMode,durationMinutes,durationAmount,durationUnit,targetLocationId,targetCharacterId,
            referencedItemIds,referencedAbilityIds,targetRealmCode,declaresGuaranteedOutcome,ambiguities,summary。
            只输出JSON对象，不输出解释。
            """;

    private final ModelGateway models;
    private final ObjectMapper mapper;

    public LangChain4jIntentInterpreter(ModelGateway models, ObjectMapper mapper) {
        this.models = models;
        this.mapper = mapper;
    }

    @Override
    public ActionIntent interpret(IntentContext context) {
        try {
            String contextJson = mapper.writeValueAsString(context);
            var result = models.completeJson(new ModelRequest("ACTION_INTERPRETATION", context.userId(),
                    context.saveId(), context.operationId(), SYSTEM_PROMPT,
                    "当前权威上下文与玩家行动：\n" + contextJson, 900, 0.1));
            IntentDraft draft = mapper.readValue(result.content(), IntentDraft.class);
            return draft.toIntent();
        } catch (JsonProcessingException error) {
            throw new V2ApiException(HttpStatus.BAD_GATEWAY, "AI_OUTPUT_INVALID", "SERVICE",
                    "行动理解结果不完整，命卷状态不会改变。", List.of("稍后使用同一行动重试"));
        }
    }

    private record IntentDraft(
            String actionType,
            String intentMode,
            Long durationMinutes,
            Long durationAmount,
            String durationUnit,
            String targetLocationId,
            String targetCharacterId,
            List<String> referencedItemIds,
            List<String> referencedAbilityIds,
            String targetRealmCode,
            Boolean declaresGuaranteedOutcome,
            List<String> ambiguities,
            String summary
    ) {
        ActionIntent toIntent() {
            return new ActionIntent(actionType, intentMode, durationMinutes, targetLocationId, targetCharacterId,
                    referencedItemIds, referencedAbilityIds, targetRealmCode,
                    Boolean.TRUE.equals(declaresGuaranteedOutcome), ambiguities, summary,
                    durationAmount, durationUnit);
        }
    }
}

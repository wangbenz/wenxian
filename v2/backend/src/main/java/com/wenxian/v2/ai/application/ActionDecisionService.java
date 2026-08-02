package com.wenxian.v2.ai.application;

import com.wenxian.v2.ai.domain.ActionAdjudication;
import com.wenxian.v2.ai.domain.ActionIntent;
import com.wenxian.v2.ai.domain.IntentContext;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import org.springframework.stereotype.Service;

@Service
public class ActionDecisionService {
    private final IntentContextAssembler contexts;
    private final IntentInterpreter interpreter;
    private final ActionAdjudicator adjudicator;

    public ActionDecisionService(IntentContextAssembler contexts, IntentInterpreter interpreter,
                                 ActionAdjudicator adjudicator) {
        this.contexts = contexts;
        this.interpreter = interpreter;
        this.adjudicator = adjudicator;
    }

    public ActionDecision decide(String userId, String operationId, String actionText, SaveAggregate aggregate) {
        IntentContext context = contexts.assemble(userId, operationId, actionText, aggregate);
        ActionIntent intent = interpreter.interpret(context);
        ActionAdjudication adjudication = adjudicator.adjudicate(context, intent);
        return new ActionDecision(intent, adjudication);
    }

    public record ActionDecision(ActionIntent intent, ActionAdjudication adjudication) {
    }
}

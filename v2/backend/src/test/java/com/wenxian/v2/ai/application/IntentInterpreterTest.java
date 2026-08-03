package com.wenxian.v2.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.v2.ai.domain.IntentContext;
import com.wenxian.v2.ai.domain.IntentContext.EntityCandidate;
import com.wenxian.v2.ai.domain.IntentContext.ProtagonistFacts;
import com.wenxian.v2.ai.domain.ModelContracts.ModelResult;
import com.wenxian.v2.ai.langchain4j.LangChain4jIntentInterpreter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentInterpreterTest {
    @Test
    void convertsStructuredModelResultWithoutOwningAnyDomainMutation() {
        ModelGateway gateway = request -> new ModelResult("""
                {"actionType":"USE_ABILITY","intentMode":"EXECUTE","durationMinutes":null,
                 "targetLocationId":null,"targetCharacterId":null,"referencedItemIds":[],
                 "referencedAbilityIds":["ability-stone-sense"],"targetRealmCode":null,
                 "declaresGuaranteedOutcome":false,"ambiguities":[],"summary":"感知矿脉"}
                """, "DEEPSEEK", "deepseek-v4-flash", "response", 100, 30, 20);
        IntentInterpreter interpreter = new LangChain4jIntentInterpreter(gateway, new ObjectMapper());
        IntentContext context = new IntentContext("user", "save", "operation", 0, "施展石脉感知",
                new ProtagonistFacts("self", "石生", "SPIRIT", "SPROUTING_SPIRIT", "萌灵",
                        "GATHERED_MIND", "聚识", "安稳", "见天地"),
                "loc-zhaogu-mine", "照骨矿", 1,
                List.of(new EntityCandidate("self", "石生", "CHARACTER", "ALIVE")), List.of(), List.of(),
                List.of(new EntityCandidate("ability-stone-sense", "石脉感知", "TALENT", "ACTIVE")));

        var intent = interpreter.interpret(context);

        assertThat(intent.actionType()).isEqualTo("USE_ABILITY");
        assertThat(intent.referencedAbilityIds()).containsExactly("ability-stone-sense");
        assertThat(intent.declaresGuaranteedOutcome()).isFalse();
    }
}

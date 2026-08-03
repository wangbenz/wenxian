package com.wenxian.v2.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.v2.ai.domain.IntentContext;
import com.wenxian.v2.ai.domain.IntentContext.EntityCandidate;
import com.wenxian.v2.ai.domain.IntentContext.ProtagonistFacts;
import com.wenxian.v2.ai.domain.ModelContracts.ModelRequest;
import com.wenxian.v2.ai.domain.ModelContracts.ModelResult;
import com.wenxian.v2.ai.domain.TurnNarrativeContext;
import com.wenxian.v2.ai.langchain4j.LangChain4jTurnProposalGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TurnProposalGeneratorTest {
    @Test
    void parsesIntentNarrativeFactsAndChoicesFromOneStructuredCall() {
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        ModelGateway gateway = request -> {
            captured.set(request);
            return new ModelResult("""
                    {
                      "intent":{"actionType":"INVESTIGATE","intentMode":"ATTEMPT","durationMinutes":90,
                        "targetLocationId":"loc-zhaogu-mine","targetCharacterId":null,
                        "referencedItemIds":[],"referencedAbilityIds":[],"targetRealmCode":null,
                        "declaresGuaranteedOutcome":false,"ambiguities":[],"summary":"查看旧矿道"},
                      "elapsedMinutes":90,"outcomeCode":"INFORMATION_DISCOVERED",
                      "outcomeSummary":"确认旧矿道仍有微弱气流",
                      "narrativeText":"你沿着潮湿石壁缓慢探查，最终确认裂缝后仍有微弱气流。",
                      "confirmedFacts":[{"domainType":"LOCATION","entityId":"loc-zhaogu-mine",
                        "fact":"照骨矿旧矿道深处仍有微弱气流"}],
                      "choices":[{"title":"继续探查","actionText":"沿气流方向继续查看"},
                        {"title":"暂时返回","actionText":"记下位置并返回安全矿道"}]
                    }
                    """, "DEEPSEEK", "deepseek-v4-flash", "request-1", 900, 320, 1800);
        };
        TurnProposalGenerator generator = new LangChain4jTurnProposalGenerator(gateway, new ObjectMapper());
        IntentContext authority = new IntentContext("user", "save", "operation", 3, "查看旧矿道",
                new ProtagonistFacts("self", "拾砾", "SPIRIT", "SPROUTING_SPIRIT", "萌灵",
                        "GATHERED_MIND", "聚识", "安稳", "离开矿脉"),
                "loc-zhaogu-mine", "照骨矿", 120,
                List.of(new EntityCandidate("self", "拾砾", "CHARACTER", "ALIVE")),
                List.of(new EntityCandidate("loc-zhaogu-mine", "照骨矿", "SITE", "UNREST")),
                List.of(), List.of());

        var generated = generator.generate(new TurnNarrativeContext("玄墟界·青岐州", "NORMAL", authority,
                List.of(), List.of(), List.of()));

        assertThat(captured.get().taskType()).isEqualTo("STORY_PLANNING");
        assertThat(captured.get().saveId()).isEqualTo("save");
        assertThat(captured.get().systemPrompt()).contains("difficulty为NORMAL", "difficulty为HARD",
                "四至七个自然段");
        assertThat(generated.proposal().intent().actionType()).isEqualTo("INVESTIGATE");
        assertThat(generated.proposal().confirmedFacts()).singleElement()
                .extracting(fact -> fact.entityId()).isEqualTo("loc-zhaogu-mine");
        assertThat(generated.proposal().choices()).extracting(choice -> choice.title())
                .containsExactly("继续探查", "暂时返回");
        assertThat(generated.modelCode()).isEqualTo("deepseek-v4-flash");
    }
}

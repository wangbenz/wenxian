package com.wenxian.game.story.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.domain.StoryCorrectionProposal.CorrectionKind;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelBackedStoryCorrectionAgentTest {
    @Test
    void parsesValidatedFactProposalFromSeparatedContext() {
        StoryModelGateway gateway = request -> {
            assertEquals(0.1, request.temperature());
            assertTrue(request.systemPrompt().contains("不执行修正"));
            assertTrue(request.userPrompt().contains("青岐城北门"));
            assertTrue(request.userPrompt().contains("当前地点应为青岐城北门"));
            return result("""
                    ```json
                    {"kind":"FACT","explanation":"修正当前地点。",
                     "confirmationPrompt":"确认将当前地点改为青岐城北门？",
                     "coreState":{"location":"青岐城北门","condition":null,"realm":null,"lifeGoal":null},
                     "entities":[],"worldFacts":[],"knowledge":[]}
                    ```
                    """);
        };
        ModelBackedStoryCorrectionAgent agent = new ModelBackedStoryCorrectionAgent(
                gateway, new ObjectMapper().findAndRegisterModules());

        StoryCorrectionAgent.Generated generated = agent.propose(context(),
                "当前地点应为青岐城北门", route());

        assertEquals(CorrectionKind.FACT, generated.proposal().kind());
        assertEquals("青岐城北门", generated.proposal().coreState().location());
        assertEquals(13, generated.modelResult().inputTokens());
    }

    @Test
    void rejectsProposalThatMixesFactAndCognitionLayers() {
        StoryModelGateway gateway = request -> result("""
                {"kind":"FACT","explanation":"同时改两个层级。","confirmationPrompt":"确认？",
                 "coreState":{"location":"青岐城北门"},"entities":[],"worldFacts":[],
                 "knowledge":[{"subjectRef":"character-1","topicRef":"fact-1",
                   "summary":"他已经知道真相","evidenceRefs":[]}]}
                """);
        ModelBackedStoryCorrectionAgent agent = new ModelBackedStoryCorrectionAgent(
                gateway, new ObjectMapper().findAndRegisterModules());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> agent.propose(context(), "修正地点，并让他知道真相", route()));

        assertEquals("修正说明未能形成可确认提案", error.getMessage());
        assertTrue(error.getCause().getMessage().contains("不能同时改写人物认知"));
    }

    @Test
    void identifiesNarrativeClarificationWithoutObjectiveChange() {
        StoryModelGateway gateway = request -> result("""
                {"kind":"COGNITION","explanation":"玩家尚未决定下一步。",
                 "confirmationPrompt":"当前权威事实未记录该决定。",
                 "coreState":{"location":null,"condition":null,"realm":null,"lifeGoal":null},
                 "entities":[],"worldFacts":[],"knowledge":[]}
                """);
        ModelBackedStoryCorrectionAgent agent = new ModelBackedStoryCorrectionAgent(
                gateway, new ObjectMapper().findAndRegisterModules());

        StoryCorrectionNoChangeException error = assertThrows(StoryCorrectionNoChangeException.class,
                () -> agent.propose(context(), "我尚未决定下一步", route()));

        assertTrue(error.getMessage().contains("权威事实"));
    }

    @Test
    void validatesRouteBeforeCallingModel() {
        assertThrows(IllegalArgumentException.class,
                () -> new StoryModelRoute("https://example.invalid", "", "DEEPSEEK",
                        "OPENAI_COMPATIBLE", "flash", "PUBLIC"));
    }

    private StoryPromptContext context() {
        return new StoryPromptContext(
                new StoryPromptContext.SystemContext(List.of("已确认事实不可静默改写"), List.of()),
                new StoryPromptContext.ObjectiveContext(120, new CoreState("青岐城南门", "平静", "引气", "问道"),
                        List.of(), List.of(), List.of()),
                new StoryPromptContext.CognitionContext(List.of()),
                new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("此前抵达城门。", null),
                new StoryPromptContext.ActionContext(""));
    }

    private StoryModelRoute route() {
        return new StoryModelRoute("https://example.invalid", "test-secret-key", "DEEPSEEK",
                "OPENAI_COMPATIBLE", "deepseek-v4-flash", "PUBLIC");
    }

    private StoryModelGateway.Result result(String text) {
        return new StoryModelGateway.Result(text, "response-1", "deepseek-v4-flash", 13, 8, 40);
    }
}

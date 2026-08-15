package com.wenxian.game.story.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.LangChain4jStoryModelGateway;
import com.wenxian.game.story.agent.ModelBackedStoryTurnAgent;
import com.wenxian.game.story.agent.StoryModelRoute;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryTurnAgent;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 独立 Flash 审阅的真实模型冒烟测试。
 *
 * <p>只有显式提供 DEEPSEEK_API_KEY 时才调用真实模型；分别验证明显越权候选会被拒绝、
 * 忠实完成玩家行动和必要自然收尾会被接受，并打印审阅自身的 Token 与延迟，不能用生成调用替代。</p>
 */
class TrustedStoryFlashReviewRealModelTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void independentFlashRejectsUnauthorizedCandidateAndAcceptsFaithfulCandidate() throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "set DEEPSEEK_API_KEY to run Flash review smoke");
        StoryModelRoute route = new StoryModelRoute(
                value(System.getenv("DEEPSEEK_BASE_URL"), "https://api.deepseek.com"), key,
                "DEEPSEEK", "OPENAI_COMPATIBLE", "deepseek-v4-pro", "TEST_ENVIRONMENT");
        StoryTurnAgent agent = new ModelBackedStoryTurnAgent(new LangChain4jStoryModelGateway(), mapper);
        String selectedScenario = value(System.getenv("WENXIAN_REAL_REVIEW_SCENARIO"), "all");

        if (selected(selectedScenario, "invalid")) {
            StoryTurnAgent.Review invalid = agent.review(restContext(), route,
                    new StoryTurnAgent.InvocationContext("flash-review-invalid", 1),
                    mapper.readValue(invalidRestCandidate(), StoryPatch.class));
            assertFalse(invalid.accepted());
            assertTrue(invalid.problem().contains("物品") || invalid.problem().contains("玩家授权"));
            print("invalid", invalid);
        }

        if (selected(selectedScenario, "valid-rest")) {
            StoryTurnAgent.Review validRest = agent.review(restContext(), route,
                    new StoryTurnAgent.InvocationContext("flash-review-valid-rest", 1),
                    mapper.readValue(validRestCandidate(), StoryPatch.class));
            assertTrue(validRest.accepted(), validRest.problem());
            print("valid-rest", validRest);
        }

        if (selected(selectedScenario, "valid")) {
            StoryTurnAgent.Review valid = agent.review(roofContext(), route,
                    new StoryTurnAgent.InvocationContext("flash-review-valid", 1),
                    mapper.readValue(validRoofCandidate(), StoryPatch.class));
            assertTrue(valid.accepted(), valid.problem());
            print("valid", valid);
        }

        if (selected(selectedScenario, "invented-decision")) {
            StoryTurnAgent.Review inventedDecision = agent.review(decisionContext(), route,
                    new StoryTurnAgent.InvocationContext("flash-review-invented-decision", 1),
                    mapper.readValue(inventedDecisionCandidate(), StoryPatch.class));
            assertFalse(inventedDecision.accepted());
            assertTrue(inventedDecision.problem().contains("玩家决定")
                            || inventedDecision.problem().contains("玩家授权"),
                    inventedDecision.problem());
            print("invented-decision", inventedDecision);
        }

        if (selected(selectedScenario, "closed-scope")) {
            StoryTurnAgent.Review closedScope = agent.review(closedScopeContext(), route,
                    new StoryTurnAgent.InvocationContext("flash-review-closed-scope", 1),
                    mapper.readValue(closedScopeCandidate(), StoryPatch.class));
            assertFalse(closedScope.accepted());
            assertTrue(closedScope.problem().contains("授权") || closedScope.problem().contains("限定"),
                    closedScope.problem());
            print("closed-scope", closedScope);
        }

        if (selected(selectedScenario, "natural-tail")) {
            StoryTurnAgent.Review naturalTail = agent.review(naturalTailContext(), route,
                    new StoryTurnAgent.InvocationContext("flash-review-natural-tail", 1),
                    mapper.readValue(naturalTailCandidate(), StoryPatch.class));
            assertTrue(naturalTail.accepted(), naturalTail.problem());
            assertTrue(naturalTail.outcome() == StoryTurnAgent.ReviewOutcome.ACCEPT
                            || naturalTail.outcome() == StoryTurnAgent.ReviewOutcome.ACCEPT_WITH_NARRATIVE_TAIL,
                    "必要自然收尾不应被判为新的重大决定");
            print("natural-tail", naturalTail);
        }
    }

    private StoryPromptContext restContext() {
        var sword = new StoryPromptContext.EntityIndexEntry("item-sword-tip", "ITEM", "断剑尖",
                "仍在行囊", true, new StoryPatch.QuantityValue(true, 1), true,
                null, StoryPatch.LifecycleState.ACTIVE);
        return new StoryPromptContext(
                new StoryPromptContext.SystemContext(List.of(), List.of()),
                new StoryPromptContext.ObjectiveContext(360,
                        StoryPromptContext.WorldClock.from(360),
                        new CoreState("破旧客房", "疲惫", "引气", null), List.of(), List.of(sword), List.of(),
                        new StoryPromptContext.InventoryView(true, List.of(sword), List.of()), null),
                new StoryPromptContext.CognitionContext(List.of()),
                new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("", null),
                new StoryPromptContext.ActionContext("只在房中休息整整一天，不修炼、不外出，也不做其他事情。",
                        1440L, StoryPromptContext.WorldClock.from(1800)));
    }

    private StoryPromptContext roofContext() {
        return new StoryPromptContext(
                new StoryPromptContext.SystemContext(List.of(), List.of()),
                new StoryPromptContext.ObjectiveContext(657,
                        StoryPromptContext.WorldClock.from(657),
                        new CoreState("破旧客房", "平静", "引气", null), List.of(), List.of(), List.of(),
                        new StoryPromptContext.InventoryView(true, List.of(), List.of()), null),
                new StoryPromptContext.CognitionContext(List.of()),
                new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("", null),
                new StoryPromptContext.ActionContext("只检查屋顶漏雨的痕迹，然后留在原地等我决定；不要离开客房。"));
    }

    private StoryPromptContext decisionContext() {
        var sword = new StoryPromptContext.EntityIndexEntry("item-sword-tip", "ITEM", "断剑尖",
                "由忘年交暂时代为保管", true, new StoryPatch.QuantityValue(true, 1), false,
                null, StoryPatch.LifecycleState.ACTIVE);
        var elder = new StoryPromptContext.EntityIndexEntry("character-elder", "CHARACTER", "老妇",
                "与你结为忘年之交，正在替你保管断剑尖", true, null, null,
                null, null, StoryPatch.LifecycleState.ACTIVE,
                new StoryPatch.RelationshipValue("忘年之交", "双方已经明确确认"));
        return new StoryPromptContext(
                new StoryPromptContext.SystemContext(List.of(), List.of()),
                new StoryPromptContext.ObjectiveContext(720,
                        StoryPromptContext.WorldClock.from(720),
                        new CoreState("老妇家中", "平静", "启灵", null), List.of(), List.of(sword, elder),
                        List.of(), new StoryPromptContext.InventoryView(true, List.of(), List.of(sword)), null),
                new StoryPromptContext.CognitionContext(List.of()),
                new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("", null),
                new StoryPromptContext.ActionContext(
                        "我向这位忘年交说明缘由，取回由她替我保管的断剑尖。拿到手后停在门口，不做其他决定。"));
    }

    private StoryPromptContext closedScopeContext() {
        var jade = new StoryPromptContext.EntityIndexEntry("item-jade", "ITEM", "残剑诀玉简",
                "随身携带", true, new StoryPatch.QuantityValue(true, 1), true,
                null, StoryPatch.LifecycleState.ACTIVE);
        return new StoryPromptContext(
                new StoryPromptContext.SystemContext(List.of(), List.of()),
                new StoryPromptContext.ObjectiveContext(720,
                        StoryPromptContext.WorldClock.from(720),
                        new CoreState("周山矮屋门前", "平静", "启灵", null), List.of(), List.of(jade), List.of(),
                        new StoryPromptContext.InventoryView(true, List.of(jade), List.of()), null),
                new StoryPromptContext.CognitionContext(List.of()),
                new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("", null),
                new StoryPromptContext.ActionContext(
                        "我只向周山点头道别，仍停在原地，不施展任何能力，也不做其他事情。"));
    }

    private StoryPromptContext naturalTailContext() {
        var jade = new StoryPromptContext.EntityIndexEntry("item-jade-slip", "ITEM", "残缺玉简",
                "正在手中用于尝试破解禁制", true, new StoryPatch.QuantityValue(true, 1), true,
                null, StoryPatch.LifecycleState.ACTIVE);
        return new StoryPromptContext(
                new StoryPromptContext.SystemContext(List.of(), List.of()),
                new StoryPromptContext.ObjectiveContext(720,
                        StoryPromptContext.WorldClock.from(720),
                        new CoreState("石室禁制前", "掌心有轻伤", "启灵", null), List.of(), List.of(jade),
                        List.of(), new StoryPromptContext.InventoryView(true, List.of(jade), List.of()), null),
                new StoryPromptContext.CognitionContext(List.of()),
                new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("", null),
                new StoryPromptContext.ActionContext("尝试用手中的残缺玉简破解眼前禁制；若失败就停下，等我决定。"));
    }

    private String invalidRestCandidate() {
        return """
                {"title":"一日静养","narrative":"你取出行囊里的干粮吃下，又点亮随身油灯。休息片刻后，你开始练习剑气凝形，并参悟残剑诀。第二日清晨，你仍留在客房。",
                 "time":{"elapsedMinutes":1440,"resolution":"COMPLETE","interruptionReason":null},
                 "coreState":{"location":null,"condition":"精神恢复","realm":null,"lifeGoal":null},
                 "entities":[],"worldFacts":[],"knowledge":[],
                 "memories":[{"memoryType":"EVENT","title":"静养修行","summary":"你休息并练习剑气凝形、参悟残剑诀。","importance":"NORMAL","evidenceRefs":[]}],
                 "decisionCheckpoint":{"required":false,"prompt":null},"choices":[],
                 "rollingSummary":"你在客房休息一日，同时练习剑气凝形并参悟残剑诀。"}
                """;
    }

    private String validRoofCandidate() {
        return """
                {"title":"檐下水痕","narrative":"你没有离开客房，只沿着屋内墙角查看漏雨留下的水痕。几处深浅不同的痕迹从屋顶延伸到窗边，你看清位置后便停下，仍留在原处，等待下一步决定。",
                 "time":{"elapsedMinutes":15,"resolution":"COMPLETE","interruptionReason":null},
                 "coreState":{"location":null,"condition":null,"realm":null,"lifeGoal":null},
                 "entities":[],"worldFacts":[],"knowledge":[],"memories":[],
                 "decisionCheckpoint":{"required":false,"prompt":null},"choices":[],
                 "rollingSummary":"你在客房内检查了屋顶漏雨痕迹，随后留在原地。"}
                """;
    }

    private String validRestCandidate() {
        return """
                {"title":"一日静养","narrative":"你没有外出，也没有修炼，只在破旧客房里安静休息。白昼过去，夜色落下又渐渐退去；到第二日清晨，你仍留在原处，疲惫已经缓解。",
                 "time":{"elapsedMinutes":1440,"resolution":"COMPLETE","interruptionReason":null},
                 "coreState":{"location":null,"condition":"疲惫缓解","realm":null,"lifeGoal":null},
                 "entities":[],"worldFacts":[],"knowledge":[],"memories":[],
                 "decisionCheckpoint":{"required":false,"prompt":null},"choices":[],
                 "rollingSummary":"你在破旧客房安静休息整整一日，没有进行其他行动。"}
                """;
    }

    private String inventedDecisionCandidate() {
        return """
                {"title":"取回断刃","narrative":"你向老妇说明来意，从她手中取回断剑尖。你把断刃收入怀中，说：‘我已经决定带它前往古战场。’老妇点头道：‘你既已决定，我便不再劝你。’随后你停在门口。",
                 "time":{"elapsedMinutes":15,"resolution":"COMPLETE","interruptionReason":null},
                 "coreState":{"location":null,"condition":null,"realm":null,"lifeGoal":null},
                 "entities":[],"worldFacts":[],"knowledge":[],"memories":[],
                 "decisionCheckpoint":{"required":false,"prompt":null},"choices":[],
                 "rollingSummary":"你从忘年交手中取回断剑尖，停在门口，尚未进行其他行动。"}
                """;
    }

    private String closedScopeCandidate() {
        return """
                {"title":"道别周山","narrative":"你向周山点头道别，仍停在原地。周山向你挥手回应。你随后从怀中取出残剑诀玉简，摩挲玉面，回忆昨夜参悟的线索，并开始思量下一步打算。片刻后，你将玉简收回怀中，依旧站在原处。",
                 "time":{"elapsedMinutes":5,"resolution":"COMPLETE","interruptionReason":null},
                 "coreState":{"location":null,"condition":null,"realm":null,"lifeGoal":null},
                 "entities":[],"worldFacts":[],"knowledge":[],"memories":[],
                 "decisionCheckpoint":{"required":false,"prompt":null},"choices":[],
                 "rollingSummary":"你向周山道别后留在原地，又取出残剑诀玉简回忆线索并思量后续。"}
                """;
    }

    private String naturalTailCandidate() {
        return """
                {"title":"禁纹反震","narrative":"你将残缺玉简贴近禁制，纹路只亮起片刻便骤然熄灭。反震令掌心旧伤微微裂开，你低头确认没有继续恶化，随即把刚才使用的玉简收回怀中，退后半步站稳。你仍停在石室禁制前，没有继续破解，也没有决定新的去向。",
                 "time":{"elapsedMinutes":15,"resolution":"COMPLETE","interruptionReason":null},
                 "coreState":{"location":null,"condition":"掌心轻伤未继续恶化","realm":null,"lifeGoal":null},
                 "entities":[],"worldFacts":[],"knowledge":[],"memories":[],
                 "decisionCheckpoint":{"required":true,"prompt":"破解失败后，你准备如何处理眼前禁制？"},"choices":[],
                 "rollingSummary":"你用残缺玉简破解禁制失败，确认伤势后收回玉简，仍停在禁制前。"}
                """;
    }

    private void print(String scenario, StoryTurnAgent.Review review) {
        var result = review.modelResults().getFirst();
        System.out.printf("FLASH_REVIEW scenario=%s accepted=%s prompt=%d completion=%d latencyMs=%d model=%s problem=%s%n",
                scenario, review.accepted(), result.inputTokens(), result.outputTokens(), result.latencyMs(),
                result.model(), review.problem());
    }

    private String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate.strip();
    }

    private boolean selected(String selected, String scenario) {
        if ("all".equalsIgnoreCase(selected)) return true;
        return java.util.Arrays.stream(selected.split(","))
                .map(String::strip)
                .anyMatch(scenario::equalsIgnoreCase);
    }
}

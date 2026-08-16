package com.wenxian.game.story.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.domain.StoryActionContract;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatchValidator;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelBackedStoryTurnAgentTest {
    private static final String VALID_PATCH = """
            {"title":"石门之后","narrative":"你运转照影诀，石门后的禁制逐层显现。",
             "time":{"elapsedMinutes":30,"resolution":"COMPLETE","interruptionReason":null},
             "coreState":{"location":null,"condition":null,"realm":null,"lifeGoal":null},
             "entities":[{"operation":"UPDATE","entityId":"ability-reflection","entityType":"ABILITY",
               "name":null,"status":"运转如常","summary":null,"attributes":null,"quantity":null,
               "inPossession":null,"abilityState":"MASTERED","lifecycle":null}],
             "worldFacts":[],"knowledge":[],"memories":[],
             "decisionCheckpoint":{"required":false,"prompt":null},
             "choices":[{"title":"继续观察","description":"寻找禁制薄弱处。"}],
             "rollingSummary":"你在石门前施展了照影诀。",
             "outcome":{"resolution":"SUCCEEDED","summary":"照影诀成功显出石门禁制"}}
            """;

    @Test
    void generatesValidatedPatchFromSeparatedContext() {
        StoryModelGateway gateway = request -> {
            assertEquals(0.7, request.temperature());
            assertTrue(request.systemPrompt().contains("第二人称“你”"));
            assertTrue(request.systemPrompt().contains("decisionCheckpoint.required=true"));
            assertTrue(request.systemPrompt().contains("objective.pendingDecision"));
            assertTrue(request.systemPrompt().contains("玩家没有在 action 中明确做出的任何后续决定"));
            assertTrue(request.systemPrompt().contains("必须在该行动完成的静止场景结束"));
            assertTrue(request.systemPrompt().contains("最终停止位置"));
            assertTrue(request.systemPrompt().contains("factContract.agencyBoundary"));
            assertTrue(request.systemPrompt().contains("不得给主角补写玩家未表达的动机"));
            assertTrue(request.systemPrompt().contains("currentInventory"));
            assertTrue(request.systemPrompt().contains("不得在一回合拆成多个重复 LANDMARK"));
            assertTrue(request.systemPrompt().contains("不得仅因本轮提到就 CREATE 新 LANDMARK"));
            assertTrue(request.userPrompt().contains("ability-reflection"));
            assertTrue(request.userPrompt().contains("是否接受使者提出的入门条件"));
            assertTrue(request.userPrompt().contains("施展照影诀"));
            assertFalse(request.userPrompt().contains("test-secret-key"));
            return result(VALID_PATCH, 101, 203, 800);
        };
        ModelBackedStoryTurnAgent agent = agent(gateway);

        StoryTurnAgent.Generated generated = agent.generate(context(), route());

        assertEquals("石门之后", generated.patch().title());
        assertEquals(TimeResolution.COMPLETE, generated.patch().time().resolution());
        assertEquals("ability-reflection", generated.patch().entities().getFirst().entityId());
        assertEquals(101, generated.inputTokens());
        assertEquals(203, generated.outputTokens());
        assertEquals(1, generated.modelResults().size());
    }

    @Test
    void outcomeFirstProtocolPlansFactsBeforeRenderingAndReviewsCoverage() {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> switch (calls.incrementAndGet()) {
            case 1 -> {
                assertEquals("deepseek-v4-flash", request.model());
                assertEquals(0.1, request.temperature());
                assertTrue(request.systemPrompt().contains("回合结果规划 Agent"));
                assertTrue(request.systemPrompt().contains("不写供玩家阅读的完整剧情"));
                assertTrue(request.systemPrompt().contains("resultTarget=true"));
                assertTrue(request.systemPrompt().contains("SUCCEEDED"));
                yield result(VALID_PATCH, 80, 120, 300);
            }
            case 2 -> {
                assertEquals(0.7, request.temperature());
                assertTrue(request.systemPrompt().contains("validatedOutcome"));
                assertTrue(request.userPrompt().contains("石门之后"));
                yield result("""
                        {"title":"禁制显影","narrative":"你运转照影诀，石门禁制在灵光中显出层叠纹路。",
                         "choices":[{"title":"继续观察","description":"寻找禁制薄弱处。"}],
                         "rollingSummary":"你在石门前完成照影，禁制纹路已经显现。"}
                        """, 90, 150, 500);
            }
            case 3 -> {
                assertEquals("deepseek-v4-flash", request.model());
                assertEquals(0.0, request.temperature());
                assertTrue(request.systemPrompt().contains("可信回合覆盖审阅 Agent"));
                assertTrue(request.userPrompt().contains("acceptedOutcome"));
                yield result("""
                        {"coverage":{"passed":true,"problem":""},
                         "contradiction":{"passed":true,"problem":""},
                         "extraDurableFacts":{"passed":true,"problem":""},
                         "agency":{"passed":true,"problem":""},"repairTarget":"NONE"}
                        """, 60, 40, 180);
            }
            default -> throw new AssertionError("结果优先协议产生了非预期模型调用");
        };
        ModelBackedStoryTurnAgent agent = agent(gateway);
        StoryTurnAgent.InvocationContext invocation = new StoryTurnAgent.InvocationContext("save-outcome", 9);

        assertTrue(agent.supportsOutcomeFirst());
        StoryTurnAgent.PlannedOutcome planned = agent.planOutcome(context(), route(), invocation);
        StoryTurnAgent.RenderedNarrative rendered = agent.renderNarrative(
                context(), route(), invocation, planned.patch());
        StoryTurnAgent.Review review = agent.reviewOutcomeCoverage(
                context(), route(), invocation, planned.patch(), rendered);

        assertEquals("石门之后", planned.patch().title());
        assertEquals(StoryPatch.OutcomeResolution.SUCCEEDED, planned.patch().outcome().resolution());
        assertEquals("禁制显影", rendered.title());
        assertTrue(review.accepted());
        assertEquals(3, calls.get());
    }

    @Test
    void normalizesCrossTypeOptionalFieldsBeforeValidatingOutcomePlan() {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> {
            calls.incrementAndGet();
            return result("""
                    {"title":"东街访人","narrative":"你沿东街寻找那名老修士。",
                     "time":{"elapsedMinutes":30,"resolution":"COMPLETE","interruptionReason":null},
                     "coreState":{"location":"青崖集东街","condition":null,"realm":null,"lifeGoal":null},
                     "entities":[{"operation":"CREATE","entityId":null,"entityType":"LANDMARK",
                       "name":"青崖集东街","status":"已经抵达","summary":"集市东侧的长街",
                       "attributes":null,"quantity":null,"inPossession":null,"custody":null,
                       "abilityState":null,"lifecycle":null,
                       "relationship":{"label":"初识","changeReason":"本轮抵达"},
                       "referenceKey":"east-street"}],
                     "worldFacts":[],"knowledge":[],"memories":[],
                     "decisionCheckpoint":{"required":false,"prompt":null},"choices":[],
                     "rollingSummary":"你已经抵达青崖集东街。",
                     "outcome":{"resolution":"SUCCEEDED","summary":"抵达东街并继续寻找老修士"}}
                    """, 80, 120, 300);
        };

        StoryTurnAgent.PlannedOutcome planned = agent(gateway).planOutcome(context(), route(),
                new StoryTurnAgent.InvocationContext("save-cross-type", 9));

        assertEquals(1, calls.get(), "可安全丢弃的跨类型字段不应触发额外模型修复");
        assertEquals("LANDMARK", planned.patch().entities().getFirst().entityType());
        assertEquals("青崖集东街", planned.patch().entities().getFirst().name());
        assertEquals(null, planned.patch().entities().getFirst().relationship());
    }

    @Test
    void repairsInvalidOutcomeContractWithCurrentFactsAndActionContext() {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> {
            if (calls.incrementAndGet() == 1) {
                return result("""
                        {"title":"得到灵石","narrative":"你拾起一块灵石。",
                         "time":{"elapsedMinutes":10,"resolution":"COMPLETE","interruptionReason":null},
                         "coreState":{"location":null,"condition":null,"realm":null,"lifeGoal":null},
                         "entities":[{"operation":"CREATE","entityId":null,"entityType":"ITEM","name":"灵石",
                           "status":null,"summary":"新拾得的灵石","attributes":null,
                           "quantity":{"known":true,"value":1},"inPossession":true,
                           "custody":{"holderType":"PLAYER","holderRef":null},"abilityState":null,
                           "lifecycle":null,"relationship":null,"referenceKey":"spirit-stone"}],
                         "worldFacts":[],"knowledge":[],"memories":[],
                         "decisionCheckpoint":{"required":false,"prompt":null},"choices":[],
                         "rollingSummary":"你拾得了一块灵石。",
                         "outcome":{"resolution":"SUCCEEDED","summary":"得到一块灵石"}}
                        """, 80, 120, 300);
            }
            assertTrue(request.userPrompt().contains("最小结果计划没有通过"));
            assertTrue(request.userPrompt().contains("objective"));
            assertTrue(request.userPrompt().contains("action"));
            assertTrue(request.userPrompt().contains("新实体必须包含当前状态"));
            return result(VALID_PATCH, 90, 140, 320);
        };

        StoryTurnAgent.PlannedOutcome planned = agent(gateway).planOutcome(context(), route(),
                new StoryTurnAgent.InvocationContext("save-contract-repair", 9));

        assertEquals(2, calls.get());
        assertEquals("石门之后", planned.patch().title());
        assertEquals(170, planned.modelResults().stream().mapToInt(StoryModelGateway.Result::inputTokens).sum());
        assertEquals(260, planned.modelResults().stream().mapToInt(StoryModelGateway.Result::outputTokens).sum());
    }

    @Test
    void coverageReviewRejectsNarrativeThatInventsDurableFacts() throws Exception {
        StoryModelGateway gateway = request -> result("""
                {"coverage":{"passed":true,"problem":""},
                 "contradiction":{"passed":true,"problem":""},
                 "extraDurableFacts":{"passed":false,"problem":"正文额外写成主角获得了一枚计划外灵丹"},
                 "agency":{"passed":true,"problem":""},"repairTarget":"NARRATIVE"}
                """, 50, 30, 120);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch plan = mapper.readValue(VALID_PATCH, StoryPatch.class);
        StoryTurnAgent.RenderedNarrative narrative = new StoryTurnAgent.RenderedNarrative(
                "计划外所得", "你完成照影后，又获得了一枚灵丹。", List.of(), "",
                List.of(result("{}", 1, 1, 1)));

        StoryTurnAgent.Review review = agent(gateway).reviewOutcomeCoverage(context(), route(),
                new StoryTurnAgent.InvocationContext("save-outcome-review", 9), plan, narrative);

        assertFalse(review.accepted());
        assertTrue(review.requiresFreshNarrative());
        assertTrue(review.problem().contains("计划外灵丹"));
    }

    @Test
    void coverageReviewRoutesMissingOutcomeBackToPlanInsteadOfRewritingNarrative() throws Exception {
        StoryModelGateway gateway = request -> result("""
                {"coverage":{"passed":false,"problem":"结果计划漏掉玩家明确要求的最终地点"},
                 "contradiction":{"passed":true,"problem":""},
                 "extraDurableFacts":{"passed":true,"problem":""},
                 "agency":{"passed":true,"problem":""},"repairTarget":"PLAN"}
                """, 50, 30, 120);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch plan = mapper.readValue(VALID_PATCH, StoryPatch.class);
        StoryTurnAgent.RenderedNarrative narrative = new StoryTurnAgent.RenderedNarrative(
                "石门之后", "你在石门前完成了观察。", List.of(), "石门前的观察已完成。",
                List.of(result("{}", 1, 1, 1)));

        StoryTurnAgent.Review review = agent(gateway).reviewOutcomeCoverage(context(), route(),
                new StoryTurnAgent.InvocationContext("save-plan-review", 10), plan, narrative);

        assertFalse(review.accepted());
        assertEquals(StoryTurnAgent.ReviewOutcome.REPAIR_FACTS, review.outcome());
        assertFalse(review.requiresFreshNarrative(), "计划缺项不能只通过改正文掩盖");
    }

    @Test
    void independentPlanAgencyReviewRejectsAnsweringReservedPlayerDecision() throws Exception {
        StoryModelGateway gateway = request -> {
            assertEquals("deepseek-v4-flash", request.model());
            assertTrue(request.systemPrompt().contains("接受交易或拜师"));
            assertTrue(request.userPrompt().contains("是否最终接受以玉简抵画款"));
            assertTrue(request.userPrompt().contains("玉简已经作为画款交付"));
            return result("""
                    {"chronologicalAgencyEvents":["老人报价","主角接受交易"],
                     "protagonistActions":["接受以玉简抵画"],
                     "protagonistDecisions":["同意交付玉简"],"protagonistMotives":[],
                     "endingState":"交易已经成立","firstRequiredStopOccurrence":"老人给出答复后",
                     "actionsAfterRequiredStop":["主角点头同意交易"],
                     "outcome":"REPAIR_DECISION","passed":false,
                     "problem":"结果计划替玩家接受了尚未确认的交易"}
                    """, 40, 30, 100);
        };
        StoryActionContract.PlayerAgencyBoundary boundary =
                new StoryActionContract.PlayerAgencyBoundary(
                        List.of("等待老人答复"), List.of(),
                        List.of("是否最终接受以玉简抵画款"), "仍在画摊前");
        StoryActionContract contract = new StoryActionContract("等待答复",
                StoryActionContract.ActionMode.QUERY, StoryActionContract.ActionScope.DO_NOW,
                false, List.of(), List.of(), List.of(), boundary, "", new StoryActionContract.Conflict(false, "", ""));
        StoryPatch plan = new ObjectMapper().findAndRegisterModules().readValue(VALID_PATCH, StoryPatch.class);
        plan = new StoryPatch(plan.title(), "老人答复后，你点头同意以玉简抵画。", plan.time(),
                plan.coreState(), plan.entities(), List.of(new StoryPatch.WorldFactPatch(
                StoryPatch.WorldFactOperation.EXPAND, null, "玉简已经作为画款交付", "交易成立")),
                plan.knowledge(), plan.memories(), plan.decisionCheckpoint(), plan.choices(),
                "玉简已经作为画款交付。", new StoryPatch.TurnOutcome(
                StoryPatch.OutcomeResolution.SUCCEEDED, "玉简已经作为画款交付"));

        StoryTurnAgent.Review review = agent(gateway).reviewOutcomePlanAgency(
                context().withActionContract(contract), route(),
                new StoryTurnAgent.InvocationContext("save-plan-agency", 12), plan);

        assertFalse(review.accepted());
        assertEquals(StoryTurnAgent.ReviewOutcome.REPAIR_DECISION, review.outcome());
        assertTrue(review.problem().contains("交易"));
    }

    @Test
    void trimsUnauthorizedOutcomeTailWithDedicatedPrompt() throws Exception {
        StoryModelGateway gateway = request -> {
            assertEquals("deepseek-v4-flash", request.model());
            assertEquals(0.0, request.temperature());
            assertTrue(request.systemPrompt().contains("授权边界裁剪 Agent"));
            assertTrue(request.systemPrompt().contains("所有相关字段中同时删除"));
            assertTrue(request.systemPrompt().contains("保留本轮已授权行动及其直接结果"));
            assertTrue(request.userPrompt().contains("玩家授权：候选替玩家登记名帖"));
            return result(VALID_PATCH, 30, 25, 80);
        };
        StoryPatch rejected = new ObjectMapper().findAndRegisterModules().readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.PlannedOutcome trimmed = agent(gateway).trimOutcomePlanToAgencyBoundary(
                context(), route(), new StoryTurnAgent.InvocationContext("save-agency-trim", 12),
                rejected, "玩家授权：候选替玩家登记名帖");

        assertEquals("石门之后", trimmed.patch().title());
        assertEquals(1, trimmed.modelResults().size());
    }

    @Test
    void repairsAgencyTrimContractOnceWithoutReplanningTheStory() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> {
            if (calls.incrementAndGet() == 1) {
                assertTrue(request.systemPrompt().contains("授权边界裁剪 Agent"));
                return result(VALID_PATCH.replace(
                        "\"required\":false,\"prompt\":null",
                        "\"required\":true,\"prompt\":null"), 25, 20, 60);
            }
            assertTrue(request.systemPrompt().contains("授权边界裁剪 Agent"));
            assertTrue(request.userPrompt().contains("重大决定检查点必须说明待玩家决定的内容"));
            return result(VALID_PATCH.replace(
                    "\"required\":false,\"prompt\":null",
                    "\"required\":true,\"prompt\":\"是否继续下一步？\""), 18, 12, 40);
        };
        StoryPatch rejected = new ObjectMapper().findAndRegisterModules().readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.PlannedOutcome trimmed = agent(gateway).trimOutcomePlanToAgencyBoundary(
                context(), route(), new StoryTurnAgent.InvocationContext("save-agency-trim-format", 12),
                rejected, "玩家授权：候选继续执行了下一步");

        assertTrue(trimmed.patch().decisionCheckpoint().required());
        assertEquals("是否继续下一步？", trimmed.patch().decisionCheckpoint().prompt());
        assertEquals(2, trimmed.modelResults().size());
    }

    @Test
    void projectsFactsWithFlashWithoutAllowingProjectionToRewriteNarrative() throws Exception {
        StoryModelGateway gateway = request -> {
            assertEquals("deepseek-v4-flash", request.model());
            assertEquals(0.0, request.temperature());
            assertTrue(request.systemPrompt().contains("可信事实投影 Agent"));
            assertTrue(request.systemPrompt().contains("禁止返回“主角”“玩家”“某人”等字符串"));
            assertTrue(request.systemPrompt().contains("quantity 禁止直接返回数字"));
            assertTrue(request.userPrompt().contains("narrativeDraft"));
            assertTrue(request.userPrompt().contains("ability-reflection"));
            assertTrue(request.userPrompt().contains("previousRollingSummary"),
                    "事实投影需要旧滚动摘要来生成连续的新摘要，但不会携带上一回完整正文");
            return result("""
                    {"title":"投影擅自改题","narrative":"投影擅自改写了正文。",
                     "time":{"elapsedMinutes":30,"resolution":"COMPLETE","interruptionReason":null},
                     "coreState":{"location":null,"condition":null,"realm":null,"lifeGoal":null},
                     "entities":[{"operation":"UPDATE","entityId":"ability-reflection","entityType":"ABILITY",
                       "name":null,"status":"运转如常","summary":null,"attributes":null,"quantity":null,
                       "inPossession":null,"custody":null,"abilityState":"MASTERED","lifecycle":null,
                       "relationship":null,"nameChange":null,"referenceKey":""}],
                     "worldFacts":[],"knowledge":[],"memories":[],
                     "decisionCheckpoint":{"required":false,"prompt":null},
                     "choices":[{"title":"被改写的选项","description":"不应展示"}],
                     "rollingSummary":"你在石门前施展了照影诀。"}
                    """, 55, 90, 180);
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch draft = mapper.readValue(VALID_PATCH, StoryPatch.class);
        StoryModelRoute proRoute = new StoryModelRoute("https://example.invalid", "test-secret-key", "DEEPSEEK",
                "OPENAI_COMPATIBLE", "deepseek-v4-pro", "PUBLIC");

        StoryTurnAgent.ProjectedFacts projected = agent(gateway).projectFacts(context(), proRoute,
                new StoryTurnAgent.InvocationContext("save-fact-projection", 9), draft);

        assertEquals(draft.title(), projected.patch().title());
        assertEquals(draft.narrative(), projected.patch().narrative());
        assertEquals(draft.choices(), projected.patch().choices());
        assertEquals(AbilityState.MASTERED, projected.patch().entities().getFirst().abilityState());
        assertEquals(1, projected.modelResults().size());
    }

    @Test
    void factProjectionUsesServerDurationInsteadOfRecalculatingCalendar() throws Exception {
        StoryModelGateway gateway = request -> result("""
                {"title":"三年闭关","narrative":"你完成了三年闭关。",
                 "time":{"elapsedMinutes":1576800,"resolution":"COMPLETE","interruptionReason":null},
                 "coreState":{"location":null,"condition":null,"realm":null,"lifeGoal":null},
                 "entities":[],"worldFacts":[],"knowledge":[],"memories":[],
                 "decisionCheckpoint":{"required":false,"prompt":null},"choices":[],
                 "rollingSummary":"你完成了三年闭关。"}
                """, 40, 70, 120);
        StoryPatch draft = new StoryPatch("三年闭关", "你完成了三年闭关。",
                new StoryPatch.TimePassage(1_576_800, TimeResolution.COMPLETE, null),
                null, List.of(), List.of(), List.of(), List.of(),
                new StoryPatch.DecisionCheckpoint(false, null), List.of(), "");
        StoryPromptContext base = context();
        StoryPromptContext durationContext = new StoryPromptContext(base.system(), base.objective(),
                base.cognition(), base.memory(), base.narrative(),
                new StoryPromptContext.ActionContext("闭关三年", 1_555_200L,
                        StoryPromptContext.WorldClock.from(1_555_320L)));

        StoryTurnAgent.ProjectedFacts projected = agent(gateway).projectFacts(durationContext, route(),
                new StoryTurnAgent.InvocationContext("save-duration-projection", 9), draft);

        assertEquals(1_555_200L, projected.patch().time().elapsedMinutes());
        assertEquals(TimeResolution.COMPLETE, projected.patch().time().resolution());
    }

    @Test
    void retriesOnlyOnceWithCompactFormatRepairAndCountsBothCalls() {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> {
            int attempt = calls.incrementAndGet();
            if (attempt == 1) return result("不是 JSON", 90, 10, 120);
            assertEquals(0.1, request.temperature());
            assertTrue(request.userPrompt().contains("只修复格式和契约字段"));
            assertTrue(request.userPrompt().contains("不是 JSON"));
            assertFalse(request.userPrompt().contains("ability-reflection"), "修复请求不应重复完整历史上下文");
            return result(VALID_PATCH, 40, 180, 300);
        };

        StoryTurnAgent.Generated generated = agent(gateway).generate(context(), route());

        assertEquals(2, generated.modelResults().size());
        assertEquals(130, generated.inputTokens());
        assertEquals(190, generated.outputTokens());
        assertEquals(420, generated.latencyMs());
    }

    @Test
    void rejectsTurnWithoutWritingWhenOriginalAndRepairAreInvalid() {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> {
            calls.incrementAndGet();
            return result("{}", 2, 2, 10);
        };

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> agent(gateway).generate(context(), route()));

        assertEquals(2, calls.get());
        assertEquals("推演结果未能形成可提交的状态提案", error.getMessage());
    }

    @Test
    void repairsReducerRejectedProposalWithoutResendingNarrativeHistory() throws Exception {
        StoryModelGateway gateway = request -> {
            assertEquals(0.1, request.temperature());
            assertTrue(request.userPrompt().contains("rejectedProposal"));
            assertTrue(request.userPrompt().contains("要改变的世界事实不存在"));
            assertTrue(request.userPrompt().contains("ability-reflection"), "修复请求必须保留稳定ID工作集");
            assertFalse(request.userPrompt().contains("你来到石门前。"), "语义修复不应重复发送叙事历史");
            return result(VALID_PATCH, 60, 120, 260);
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch rejected = mapper.readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.Generated repaired = agent(gateway).repairRejected(context(), route(),
                new StoryTurnAgent.InvocationContext("save-semantic-repair", 9), rejected,
                "要改变的世界事实不存在：青岐州北境异象");

        assertEquals("石门之后", repaired.patch().title());
        assertEquals(60, repaired.inputTokens());
        assertEquals(120, repaired.outputTokens());
    }

    @Test
    void regeneratesRepeatedNarrativeWithoutSendingPreviousOrRejectedNarrative() throws Exception {
        StoryModelGateway gateway = request -> {
            assertEquals(0.7, request.temperature());
            assertTrue(request.userPrompt().contains("从 objective 的当前状态与 action 重新构思"));
            assertTrue(request.userPrompt().contains("ability-reflection"), "重新生成仍需保留当前稳定ID");
            assertTrue(request.userPrompt().contains("施展照影诀"));
            assertFalse(request.userPrompt().contains("你来到石门前。"), "不应再发送上一回正文");
            assertFalse(request.userPrompt().contains("你运转照影诀，石门后的禁制逐层显现。"),
                    "不应再发送被拒绝的重复正文");
            return result(VALID_PATCH, 55, 150, 240);
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch rejected = mapper.readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.Generated regenerated = agent(gateway).regenerateFreshNarrative(context(), route(),
                new StoryTurnAgent.InvocationContext("save-fresh-regeneration", 9), rejected,
                "本轮正文与上一回合高度重复");

        assertEquals(1, regenerated.modelResults().size());
        assertEquals(55, regenerated.inputTokens());
        assertEquals(150, regenerated.outputTokens());
    }

    @Test
    void repairsMalformedFreshRegenerationOnceWithoutRestartingTheWholeTurn() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> {
            if (calls.incrementAndGet() == 1) {
                assertEquals(0.7, request.temperature());
                return result("{}", 52, 8, 150);
            }
            assertEquals(0.1, request.temperature());
            assertTrue(request.userPrompt().contains("只修复格式和契约字段"));
            return result(VALID_PATCH, 31, 130, 220);
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch rejected = mapper.readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.Generated regenerated = agent(gateway).regenerateFreshNarrative(context(), route(),
                new StoryTurnAgent.InvocationContext("save-fresh-format-repair", 9), rejected,
                "本轮正文与上一回合高度重复");

        assertEquals(2, regenerated.modelResults().size());
        assertEquals(83, regenerated.inputTokens());
        assertEquals(138, regenerated.outputTokens());
    }

    @Test
    void repairsMalformedSemanticRepairOnceWithoutRestartingTheWholeTurn() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> {
            if (calls.incrementAndGet() == 1) return result("{}", 48, 7, 140);
            assertEquals(0.1, request.temperature());
            assertTrue(request.userPrompt().contains("只修复格式和契约字段"));
            return result(VALID_PATCH, 29, 125, 210);
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch rejected = mapper.readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.Generated repaired = agent(gateway).repairRejected(context(), route(),
                new StoryTurnAgent.InvocationContext("save-semantic-format-repair", 9), rejected,
                "只有人物实体可以记录关系变化");

        assertEquals(2, repaired.modelResults().size());
        assertEquals(77, repaired.inputTokens());
        assertEquals(132, repaired.outputTokens());
    }

    @Test
    void independentlyReviewsActionAuthorityAndObjectiveFacts() throws Exception {
        StoryModelGateway gateway = request -> {
            assertEquals(0.0, request.temperature());
            assertEquals("deepseek-v4-flash", request.model());
            if (request.systemPrompt().contains("玩家授权复核 Agent")) {
                assertTrue(request.systemPrompt().contains("自然收尾"));
                assertTrue(request.userPrompt().contains("decisionEnvelope"));
                return result("""
                        {"chronologicalAgencyEvents":["施展照影诀","追问古战场"],
                         "protagonistActions":["施展照影诀","追问古战场"],
                         "protagonistDecisions":[],"protagonistMotives":[],"endingState":"石门前",
                         "firstRequiredStopOccurrence":"观察完成","actionsAfterRequiredStop":["追问古战场"],
                         "outcome":"REPAIR_DECISION","passed":false,"problem":"正文擅自替玩家追问了古战场"}
                        """, 20, 14, 55);
            }
            assertTrue(request.systemPrompt().contains("独立 Flash 回合审阅"));
            assertTrue(request.systemPrompt().contains("必须逐项完成四类重大事实检查"));
            assertTrue(request.systemPrompt().contains("把新瓦片之类对象误绑成历史石子、玉简"));
            assertTrue(request.systemPrompt().contains("普通观察最多形成一个"));
            assertTrue(request.systemPrompt().contains("仅因观察而把屋檐、门框、墙角、路面"));
            assertTrue(request.userPrompt().contains("currentInventory"));
            assertTrue(request.userPrompt().contains("proposed"));
            assertFalse(request.userPrompt().contains("你来到石门前。"), "独立审阅不应重复发送上一回正文");
            return result("""
                    {"reference":{"passed":true,"problem":""},
                     "inventory":{"passed":true,"problem":""},
                     "time":{"passed":true,"problem":""},
                     "state":{"passed":true,"problem":""}}
                    """, 45, 18, 120);
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch proposed = mapper.readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.Review review = agent(gateway).review(context(), route(),
                new StoryTurnAgent.InvocationContext("save-review", 9), proposed);

        assertFalse(review.accepted());
        assertEquals("玩家授权：正文擅自替玩家追问了古战场", review.problem());
        assertEquals(2, review.modelResults().size());
    }

    @Test
    void rejectsNarrativeThatBindsANewMentionToTheWrongHistoricalEntity() throws Exception {
        StoryModelGateway gateway = request -> result("""
                {"reference":{"passed":false,"problem":"玩家提到的是新出现的青色瓦片，候选却把它写成历史玉简"},
                 "inventory":{"passed":true,"problem":""},
                 "authorization":{"passed":true,"problem":""},
                 "decision":{"passed":true,"problem":""},
                 "time":{"passed":true,"problem":""},
                 "state":{"passed":true,"problem":""}}
                """, 39, 15, 91);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch proposed = mapper.readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.Review review = agent(gateway).review(context(), route(),
                new StoryTurnAgent.InvocationContext("save-review-reference", 10), proposed);

        assertFalse(review.accepted());
        assertEquals("实体引用：玩家提到的是新出现的青色瓦片，候选却把它写成历史玉简", review.problem());
    }

    @Test
    void resolvesItemCustodyContractWithStableReferencesBeforeGeneration() {
        StoryModelGateway gateway = request -> {
            assertEquals(0.0, request.temperature());
            assertEquals("deepseek-v4-flash", request.model());
            assertTrue(request.systemPrompt().contains("行动事实解析 Agent"));
            assertTrue(request.systemPrompt().contains("agencyBoundary 必须完整解释玩家授权"));
            assertTrue(request.userPrompt().contains("ability-reflection"));
            assertTrue(request.userPrompt().contains("施展照影诀"));
            assertTrue(request.userPrompt().contains("\"coreState\""));
            assertTrue(request.userPrompt().contains("石门前"),
                    "行动契约必须知道玩家当前场景，否则地标会被误判为新物品");
            assertTrue(request.systemPrompt().contains("普通组成部分，玩家仅观察时不得返回 NEW"));
            return result("""
                    {"intentSummary":"施展已有能力观察石门","itemActionDetected":false,
                     "references":[{"mention":"照影诀","resolution":"EXISTING","entityId":"ability-reflection",
                       "entityType":"ABILITY","canonicalName":"照影诀","role":"SOURCE","expectedCustody":null}],
                     "itemTransitions":[],"characterPreconditions":[],
                     "agencyBoundary":{"authorizedActions":["施展照影诀观察石门"],
                       "prohibitedActions":["离开石门"],"unresolvedDecisions":["是否接受入门条件"],
                       "requiredEndState":"观察完成后仍停在石门前"},
                     "stopBoundary":"观察完成后停下",
                     "conflict":{"present":false,"reason":"","correction":""}}
                    """, 38, 20, 90);
        };

        StoryTurnAgent.ResolvedAction resolved = agent(gateway).resolveAction(context(), route(),
                new StoryTurnAgent.InvocationContext("save-contract", 9));

        assertFalse(resolved.contract().itemActionDetected());
        assertEquals("ability-reflection", resolved.contract().references().getFirst().entityId());
        assertEquals(List.of("施展照影诀观察石门"),
                resolved.contract().agencyBoundary().authorizedActions());
        assertEquals("观察完成后仍停在石门前", resolved.contract().agencyBoundary().requiredEndState());
        assertEquals(1, resolved.modelResults().size());
    }

    @Test
    void repairsActionContractWhenModelConfusesResolutionWithPersistence() {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> {
            if (calls.getAndIncrement() == 0) {
                return result("""
                        {"intentSummary":"询问普通摊主","actionMode":"ATTEMPT","itemActionDetected":false,
                         "references":[{"mention":"摊主","resolution":"TRANSIENT","entityId":null,
                           "entityType":"CHARACTER","canonicalName":"摊主","role":"SCENE",
                           "expectedCustody":null,"evidenceRefs":[],"referenceKey":"scene-owner"}],
                         "itemTransitions":[],"characterPreconditions":[],"stopBoundary":"问完后停下",
                         "conflict":{"present":false,"reason":"","correction":""}}
                        """, 20, 10, 40);
            }
            assertTrue(request.userPrompt().contains("malformedContract"));
            assertTrue(request.userPrompt().contains("resolution 只允许"));
            return result("""
                    {"intentSummary":"询问普通摊主","actionMode":"ATTEMPT","itemActionDetected":false,
                     "references":[{"mention":"摊主","resolution":"NEW","entityId":null,
                       "entityType":"CHARACTER","canonicalName":"摊主","role":"SCENE",
                       "expectedCustody":null,"evidenceRefs":[],"referenceKey":"scene-owner",
                       "persistence":"TRANSIENT","resultTarget":false}],
                     "itemTransitions":[],"characterPreconditions":[],"stopBoundary":"问完后停下",
                     "conflict":{"present":false,"reason":"","correction":""}}
                    """, 30, 15, 50);
        };

        StoryTurnAgent.ResolvedAction resolved = agent(gateway).resolveAction(context(), route(),
                new StoryTurnAgent.InvocationContext("save-contract-format", 10));

        assertEquals(2, resolved.modelResults().size());
        assertEquals(StoryActionContract.ReferenceResolution.NEW,
                resolved.contract().references().getFirst().resolution());
        assertEquals(StoryActionContract.ReferencePersistence.TRANSIENT,
                resolved.contract().references().getFirst().persistence());
    }

    @Test
    void repairsActionContractWithoutBindingSourceCharacterToAnotherStableId() {
        StoryModelGateway gateway = request -> {
            assertEquals("deepseek-v4-flash", request.model());
            assertTrue(request.userPrompt().contains("rejectedContract"));
            assertTrue(request.userPrompt().contains("不得猜测另一个已有角色"));
            return result("""
                    {"intentSummary":"把玉坠交给柳青儿","actionMode":"ATTEMPT","itemActionDetected":true,
                     "references":[
                       {"mention":"柳字玉坠","resolution":"EXISTING","entityId":"item-jade",
                        "entityType":"ITEM","canonicalName":"柳字玉坠","role":"SOURCE",
                        "expectedCustody":{"holderType":"PLAYER","holderRef":null},"evidenceRefs":[],"referenceKey":"r1"},
                       {"mention":"柳青儿","resolution":"ESTABLISHED","entityId":null,
                        "entityType":"CHARACTER","canonicalName":"柳青儿","role":"TARGET",
                        "expectedCustody":null,"evidenceRefs":["initial:origin"],"referenceKey":"r2"}],
                     "itemTransitions":[{"itemId":"item-jade",
                       "expectedFrom":{"holderType":"PLAYER","holderRef":null},
                       "target":{"holderType":"CHARACTER","holderRef":null},"quantity":1,"targetReferenceKey":"r2"}],
                     "characterPreconditions":[],
                     "agencyBoundary":{"authorizedActions":["把玉坠交给柳青儿"],"prohibitedActions":[],
                       "unresolvedDecisions":[],"requiredEndState":"玉坠由柳青儿保管"},
                     "stopBoundary":"交付后停下","conflict":{"present":false,"reason":"","correction":""}}
                    """, 44, 30, 110);
        };
        StoryActionContract rejected = new StoryActionContract("把玉坠交给柳青儿", true,
                List.of(), List.of(), List.of(), "", new StoryActionContract.Conflict(false, "", ""));

        StoryTurnAgent.ResolvedAction repaired = agent(gateway).repairActionContract(
                context(), route(), new StoryTurnAgent.InvocationContext("save-contract-repair", 9),
                rejected, "目标人物没有稳定引用");

        assertEquals(StoryActionContract.ReferenceResolution.ESTABLISHED,
                repaired.contract().references().get(1).resolution());
        assertEquals("r2", repaired.contract().itemTransitions().getFirst().targetReferenceKey());
        assertEquals(List.of("initial:origin"), repaired.contract().references().get(1).evidenceRefs());
    }

    @Test
    void usesIndependentFlashReviewerEvenWhenNarrativeUsesPro() throws Exception {
        StoryModelGateway gateway = request -> {
            assertEquals("deepseek-v4-flash", request.model());
            if (request.systemPrompt().contains("玩家授权复核 Agent")) {
                assertTrue(request.userPrompt().contains("factContract"));
                assertTrue(request.userPrompt().contains("narrative"));
                return result("""
                        {"chronologicalAgencyEvents":["施展照影诀","停在石门前"],
                         "protagonistActions":["施展照影诀"],"protagonistDecisions":[],
                         "protagonistMotives":[],"endingState":"仍在石门前",
                         "firstRequiredStopOccurrence":"","actionsAfterRequiredStop":[],
                         "passed":true,"problem":""}
                        """, 18, 12, 45);
            }
            return result("""
                    {"reference":{"passed":true,"problem":""},
                     "inventory":{"passed":true,"problem":""},
                     "authorization":{"passed":true,"problem":""},
                     "decision":{"passed":true,"problem":""},
                     "time":{"passed":true,"problem":""},
                     "state":{"passed":true,"problem":""}}
                    """, 40, 10, 90);
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch proposed = mapper.readValue(VALID_PATCH, StoryPatch.class);
        StoryModelRoute proRoute = new StoryModelRoute("https://example.invalid", "test-secret-key", "DEEPSEEK",
                "OPENAI_COMPATIBLE", "deepseek-v4-pro", "PUBLIC");

        StoryTurnAgent.Review review = agent(gateway).review(context(), proRoute,
                new StoryTurnAgent.InvocationContext("save-review-pro", 10), proposed);

        assertTrue(review.accepted());
        assertEquals(2, review.modelResults().size());
    }

    @Test
    void dedicatedAgencyReviewRejectsReturnTripMissedByGeneralReview() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> {
            if (calls.incrementAndGet() == 1) {
                return result("""
                        {"reference":{"passed":true,"problem":""},
                         "inventory":{"passed":true,"problem":""},
                         "authorization":{"passed":true,"problem":""},
                         "decision":{"passed":true,"problem":""},
                         "time":{"passed":true,"problem":""},
                         "state":{"passed":true,"problem":""}}
                        """, 40, 10, 90);
            }
            assertTrue(request.systemPrompt().contains("完整提取"));
            assertTrue(request.systemPrompt().contains("不能因为候选最后声称"));
            return result("""
                    {"chronologicalAgencyEvents":["沿村东小路探索","到达杂木林口","转身返回水井"],
                     "protagonistActions":["沿村东小路探索","转身沿原路返回水井"],
                     "protagonistDecisions":["选择不进入杂木林并返程"],
                     "protagonistMotives":[],"endingState":"回到水井旁",
                     "firstRequiredStopOccurrence":"到达杂木林岔路",
                     "actionsAfterRequiredStop":["转身沿原路返回水井"],
                     "passed":false,"problem":"候选擅自返程，违反不返程并停在抵达位置的授权边界"}
                    """, 22, 18, 55);
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch proposed = mapper.readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.Review review = agent(gateway).review(context(), route(),
                new StoryTurnAgent.InvocationContext("save-agency-review", 10), proposed);

        assertFalse(review.accepted());
        assertTrue(review.requiresFreshNarrative());
        assertTrue(review.problem().contains("擅自返程"));
        assertEquals(2, review.modelResults().size());
    }

    @Test
    void rejectsAgencyReviewThatClaimsPassAfterActionsBeyondRequiredStop() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> switch (calls.incrementAndGet()) {
            case 1 -> result("""
                        {"reference":{"passed":true,"problem":""},"inventory":{"passed":true,"problem":""},
                         "authorization":{"passed":true,"problem":""},"decision":{"passed":true,"problem":""},
                         "time":{"passed":true,"problem":""},"state":{"passed":true,"problem":""}}
                        """, 30, 10, 50);
            case 2 -> result("""
                        {"chronologicalAgencyEvents":["到达第一处岔路","选择主路继续前行"],
                         "protagonistActions":["继续前行"],"protagonistDecisions":["选择主路"],
                         "protagonistMotives":[],"endingState":"第二处岔路",
                         "firstRequiredStopOccurrence":"第一处岔路","actionsAfterRequiredStop":["选择主路继续前行"],
                         "passed":true,"problem":""}
                        """, 20, 15, 45);
            default -> {
                assertTrue(request.systemPrompt().contains("授权复核仲裁 Agent"));
                assertTrue(request.userPrompt().contains("originalReview"));
                yield result("""
                        {"chronologicalAgencyEvents":["到达第一处岔路","选择主路继续前行"],
                         "protagonistActions":["继续前行"],"protagonistDecisions":["选择主路"],
                         "protagonistMotives":[],"endingState":"第二处岔路",
                         "firstRequiredStopOccurrence":"第一处岔路","actionsAfterRequiredStop":["选择主路继续前行"],
                         "outcome":"REPAIR_DECISION","passed":false,
                         "problem":"候选替玩家选择主路并继续前行"}
                        """, 18, 12, 35);
            }
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch proposed = mapper.readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.Review review = agent(gateway).review(context(), route(),
                new StoryTurnAgent.InvocationContext("save-bad-agency-pass", 10), proposed);

        assertFalse(review.accepted());
        assertTrue(review.requiresFreshNarrative());
        assertTrue(review.problem().contains("选择主路"));
        assertEquals(3, review.modelResults().size());
    }

    @Test
    void reconcilesHarmlessInformationTailWithoutBlockingQueryTurn() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> {
            if (calls.incrementAndGet() == 1) {
                return result("""
                        {"chronologicalAgencyEvents":["向引路道人询问玄微道人的生平","得知玄微道人过往"],
                         "protagonistActions":["询问玄微道人的生平"],"protagonistDecisions":[],
                         "protagonistMotives":[],"endingState":"仍在青云观正殿前等候",
                         "firstRequiredStopOccurrence":"引路道人回答询问",
                         "actionsAfterRequiredStop":["得知玄微道人过往"],
                         "outcome":"ACCEPT","passed":true,"problem":""}
                        """, 24, 16, 40);
            }
            assertTrue(request.systemPrompt().contains("授权复核仲裁 Agent"));
            assertTrue(request.systemPrompt().contains("已有死亡消息被进一步确认"));
            assertTrue(request.userPrompt().contains("originalReview"));
            return result("""
                    {"chronologicalAgencyEvents":["向引路道人询问玄微道人的生平","引路道人回答"],
                     "protagonistActions":["询问玄微道人的生平"],"protagonistDecisions":[],
                     "protagonistMotives":[],"endingState":"仍在青云观正殿前等候",
                     "firstRequiredStopOccurrence":"引路道人回答询问",
                     "actionsAfterRequiredStop":["听到询问的直接答复"],
                     "outcome":"ACCEPT_WITH_NARRATIVE_TAIL","passed":true,"problem":""}
                    """, 18, 12, 30);
        };
        StoryActionContract.PlayerAgencyBoundary boundary =
                new StoryActionContract.PlayerAgencyBoundary(
                        List.of("向引路道人询问玄微道人的生平", "了解玄微道人与青云观的渊源"),
                        List.of(), List.of(), "仍位于青云观正殿前，等待主持安排");
        StoryActionContract contract = new StoryActionContract("询问玄微道人生平",
                StoryActionContract.ActionMode.QUERY, StoryActionContract.ActionScope.DO_NOW,
                false, List.of(), List.of(), List.of(), boundary, "", new StoryActionContract.Conflict(false, "", ""));
        StoryPatch plan = new ObjectMapper().findAndRegisterModules().readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.Review review = agent(gateway).reviewOutcomePlanAgency(
                context().withActionContract(contract), route(),
                new StoryTurnAgent.InvocationContext("save-query-tail", 13), plan);

        assertTrue(review.accepted());
        assertEquals(StoryTurnAgent.ReviewOutcome.ACCEPT_WITH_NARRATIVE_TAIL, review.outcome());
        assertEquals(2, review.modelResults().size());
    }

    @Test
    void keepsSafeInitialAgencyVerdictWhenOptionalReconciliationIsUnavailable() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> {
            if (calls.incrementAndGet() == 1) {
                return result("""
                        {"chronologicalAgencyEvents":["询问旧事","听到直接答复"],
                         "protagonistActions":["询问旧事"],"protagonistDecisions":[],
                         "protagonistMotives":[],"endingState":"仍在原地",
                         "firstRequiredStopOccurrence":"对方作出答复",
                         "actionsAfterRequiredStop":["听到直接答复"],
                         "outcome":"ACCEPT","passed":true,"problem":""}
                        """, 20, 12, 35);
            }
            throw new IllegalStateException("仲裁服务暂时不可用");
        };
        StoryPatch plan = new ObjectMapper().findAndRegisterModules().readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.Review review = agent(gateway).reviewOutcomePlanAgency(
                context(), route(), new StoryTurnAgent.InvocationContext("save-query-fallback", 14), plan);

        assertTrue(review.accepted());
        assertEquals(StoryTurnAgent.ReviewOutcome.ACCEPT_WITH_NARRATIVE_TAIL, review.outcome());
        assertEquals(1, review.modelResults().size());
    }

    @Test
    void arbitratesFalseRejectionAfterOutcomePlanWasAlreadyRepaired() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> {
            if (calls.incrementAndGet() == 1) {
                return result("""
                        {"chronologicalAgencyEvents":["向路过弟子询问","得知需要登记名帖"],
                         "protagonistActions":["前往偏殿登记名帖"],
                         "protagonistDecisions":["决定立即登记"],"protagonistMotives":[],
                         "endingState":"正殿前等候","firstRequiredStopOccurrence":"弟子作出答复",
                         "actionsAfterRequiredStop":["前往偏殿登记名帖"],
                         "outcome":"REPAIR_DECISION","passed":false,
                         "problem":"候选替玩家前往偏殿登记名帖"}
                        """, 20, 15, 40);
            }
            assertTrue(request.systemPrompt().contains("不得引用整理前的旧候选"));
            assertTrue(request.userPrompt().contains("originalReview"));
            assertTrue(request.userPrompt().contains("尚未登记"));
            return result("""
                    {"chronologicalAgencyEvents":["向路过弟子询问","得知需要登记名帖"],
                     "protagonistActions":["向路过弟子询问"],"protagonistDecisions":[],
                     "protagonistMotives":[],"endingState":"仍在正殿前，尚未登记",
                     "firstRequiredStopOccurrence":"弟子作出答复",
                     "actionsAfterRequiredStop":["听到询问的直接答复"],
                     "outcome":"ACCEPT_WITH_NARRATIVE_TAIL","passed":true,"problem":""}
                    """, 18, 12, 35);
        };
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch base = mapper.readValue(VALID_PATCH, StoryPatch.class);
        StoryPatch repaired = new StoryPatch(base.title(),
                "你向路过弟子询问，得知需先登记名帖。你仍在正殿前，尚未登记。",
                base.time(), base.coreState(), base.entities(), base.worldFacts(), base.knowledge(),
                base.memories(), new StoryPatch.DecisionCheckpoint(true, "是否前往偏殿登记名帖？"),
                base.choices(), "已得知需登记名帖，但尚未登记。", base.outcome());

        StoryTurnAgent.Review review = agent(gateway).reviewRepairedOutcomePlanAgency(
                context(), route(), new StoryTurnAgent.InvocationContext("save-repaired-agency", 15), repaired);

        assertTrue(review.accepted());
        assertEquals(StoryTurnAgent.ReviewOutcome.ACCEPT_WITH_NARRATIVE_TAIL, review.outcome());
        assertEquals(2, review.modelResults().size());
    }

    @Test
    void keepsRepairedPlanRejectedWhenFinalArbitrationIsUnavailable() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> {
            if (calls.incrementAndGet() == 1) {
                return result("""
                        {"chronologicalAgencyEvents":["得到答复","前往偏殿"],
                         "protagonistActions":["前往偏殿"],"protagonistDecisions":["决定登记"],
                         "protagonistMotives":[],"endingState":"偏殿",
                         "firstRequiredStopOccurrence":"得到答复",
                         "actionsAfterRequiredStop":["前往偏殿"],
                         "outcome":"REPAIR_DECISION","passed":false,"problem":"替玩家前往偏殿"}
                        """, 20, 14, 35);
            }
            throw new IllegalStateException("最终仲裁暂时不可用");
        };
        StoryPatch plan = new ObjectMapper().findAndRegisterModules().readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.Review review = agent(gateway).reviewRepairedOutcomePlanAgency(
                context(), route(), new StoryTurnAgent.InvocationContext("save-repaired-reject", 16), plan);

        assertFalse(review.accepted());
        assertEquals(StoryTurnAgent.ReviewOutcome.REPAIR_DECISION, review.outcome());
        assertEquals(1, review.modelResults().size());
    }

    @Test
    void acceptsNaturalNarrativeTailAfterAuthorizedActionCompletes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> calls.incrementAndGet() == 1
                ? result("""
                        {"reference":{"passed":true,"problem":""},"inventory":{"passed":true,"problem":""},
                         "time":{"passed":true,"problem":""},"state":{"passed":true,"problem":""}}
                        """, 30, 10, 50)
                : result("""
                        {"chronologicalAgencyEvents":["破解失败","查看掌心伤势","收回刚才使用的玉简","退后半步站稳"],
                         "protagonistActions":["尝试破解禁制","查看掌心伤势","收回玉简","退后半步站稳"],
                         "protagonistDecisions":[],"protagonistMotives":[],"endingState":"仍在禁制前",
                         "firstRequiredStopOccurrence":"破解失败",
                         "actionsAfterRequiredStop":["查看掌心伤势","收回玉简","退后半步站稳"],
                         "outcome":"ACCEPT_WITH_NARRATIVE_TAIL","passed":true,"problem":""}
                        """, 22, 18, 55);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch proposed = mapper.readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.Review review = agent(gateway).review(context(), route(),
                new StoryTurnAgent.InvocationContext("save-natural-tail", 10), proposed);

        assertTrue(review.accepted());
        assertFalse(review.requiresFreshNarrative());
        assertEquals(StoryTurnAgent.ReviewOutcome.ACCEPT_WITH_NARRATIVE_TAIL, review.outcome());
        assertEquals(2, review.modelResults().size());
    }

    @Test
    void rejectsNarrativeThatInventsAnUnstatedPlayerDecision() throws Exception {
        StoryModelGateway gateway = request -> request.systemPrompt().contains("玩家授权复核 Agent")
                ? result("""
                        {"chronologicalAgencyEvents":["说明缘由","取回物品","决定前往古战场"],
                         "protagonistActions":["说明缘由","取回物品"],
                         "protagonistDecisions":["决定前往古战场"],"protagonistMotives":[],
                         "endingState":"原地","firstRequiredStopOccurrence":"取回物品后",
                         "actionsAfterRequiredStop":["决定前往古战场"],
                         "outcome":"REPAIR_DECISION","passed":false,
                         "problem":"玩家只要求说明缘由并取回物品，正文却补成已经决定前往古战场"}
                        """, 24, 18, 70)
                : result("""
                        {"reference":{"passed":true,"problem":""},
                         "inventory":{"passed":true,"problem":""},
                         "time":{"passed":true,"problem":""},
                         "state":{"passed":true,"problem":""}}
                        """, 42, 16, 95);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch proposed = mapper.readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.Review review = agent(gateway).review(context(), route(),
                new StoryTurnAgent.InvocationContext("save-review-decision", 10), proposed);

        assertFalse(review.accepted());
        assertEquals("玩家授权：玩家只要求说明缘由并取回物品，正文却补成已经决定前往古战场", review.problem());
    }

    @Test
    void rejectsIncompleteReviewContractInsteadOfSilentlyAccepting() throws Exception {
        StoryModelGateway gateway = request -> result("""
                {"reference":{"passed":true,"problem":""},
                 "inventory":{"passed":true,"problem":""},
                 "authorization":{"passed":true,"problem":""}}
                """, 20, 8, 60);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch proposed = mapper.readValue(VALID_PATCH, StoryPatch.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> agent(gateway).review(context(), route(),
                        new StoryTurnAgent.InvocationContext("save-review-incomplete", 11), proposed));

        assertTrue(error.getMessage().contains("缺少“时间”检查结果"));
    }

    @Test
    void reportsInitialRepairAndFailedRequestsAsSeparateModelFacts() {
        AtomicInteger calls = new AtomicInteger();
        ArrayList<String> succeeded = new ArrayList<>();
        ArrayList<String> failed = new ArrayList<>();
        StoryModelInvocationRecorder recorder = new StoryModelInvocationRecorder() {
            @Override
            public void succeeded(StoryTurnAgent.InvocationContext invocation, String operation,
                                  StoryModelRoute route, StoryModelGateway.Result result) {
                assertEquals("save-observed", invocation.saveId());
                assertEquals(7, invocation.turnNumber());
                succeeded.add(operation);
            }

            @Override
            public void failed(StoryTurnAgent.InvocationContext invocation, String operation,
                               StoryModelRoute route, long latencyMs, RuntimeException failure) {
                failed.add(operation);
            }
        };
        StoryModelGateway repairedGateway = request -> calls.incrementAndGet() == 1
                ? result("not-json", 20, 5, 10)
                : result(VALID_PATCH, 30, 100, 20);
        ModelBackedStoryTurnAgent repairedAgent = new ModelBackedStoryTurnAgent(repairedGateway, recorder,
                new ObjectMapper().findAndRegisterModules(), new StoryPatchValidator());

        repairedAgent.generate(context(), route(), new StoryTurnAgent.InvocationContext("save-observed", 7));

        assertEquals(List.of("STORY_TURN", "STORY_TURN_REPAIR"), succeeded);
        assertTrue(failed.isEmpty());

        StoryModelGateway failedGateway = request -> {
            throw new StoryModelFailureException(StoryModelFailureException.Kind.RATE_LIMITED,
                    "internal diagnostic", null);
        };
        ModelBackedStoryTurnAgent failedAgent = new ModelBackedStoryTurnAgent(failedGateway, recorder,
                new ObjectMapper().findAndRegisterModules(), new StoryPatchValidator());

        assertThrows(StoryModelFailureException.class, () -> failedAgent.generate(context(), route(),
                new StoryTurnAgent.InvocationContext("save-observed", 7)));
        assertEquals(List.of("STORY_TURN"), failed);
    }

    private ModelBackedStoryTurnAgent agent(StoryModelGateway gateway) {
        return new ModelBackedStoryTurnAgent(gateway, new ObjectMapper().findAndRegisterModules());
    }

    private StoryPromptContext context() {
        return new StoryPromptContext(
                new StoryPromptContext.SystemContext(List.of("已确认事实不可静默改写"), List.of()),
                new StoryPromptContext.ObjectiveContext(120,
                        new CoreState("石门前", "平静", "引气", "问道"), List.of(),
                        List.of(new StoryPromptContext.EntityIndexEntry("ability-reflection", "ABILITY", "照影诀",
                                "运转如常", true, null, null,
                                com.wenxian.game.story.domain.StoryPatch.AbilityState.MASTERED, null)),
                        List.of(), new StoryPromptContext.PendingDecision("是否接受使者提出的入门条件？")),
                new StoryPromptContext.CognitionContext(List.of()),
                new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("你来到石门前。", null),
                new StoryPromptContext.ActionContext("施展照影诀查看石门后的禁制。"));
    }

    private StoryModelRoute route() {
        return new StoryModelRoute("https://example.invalid", "test-secret-key", "DEEPSEEK",
                "OPENAI_COMPATIBLE", "deepseek-v4-flash", "PUBLIC");
    }

    private StoryModelGateway.Result result(String text, int input, int output, long latency) {
        return new StoryModelGateway.Result(text, "response-1", "deepseek-v4-flash", input, output, latency);
    }
}

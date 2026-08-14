package com.wenxian.game.story.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.domain.StoryActionContract;
import com.wenxian.game.story.domain.StoryPatch;
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
             "rollingSummary":"你在石门前施展了照影诀。"}
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
            assertTrue(request.systemPrompt().contains("独立 Flash 回合审阅"));
            assertTrue(request.systemPrompt().contains("必须逐项完成六类检查"));
            assertTrue(request.systemPrompt().contains("把新瓦片之类对象误绑成历史石子、玉简"));
            assertTrue(request.systemPrompt().contains("理由、意图、目标、计划、立场或承诺"));
            assertTrue(request.systemPrompt().contains("普通观察最多形成一个"));
            assertTrue(request.systemPrompt().contains("仅因观察而把屋檐、门框、墙角、路面"));
            assertTrue(request.userPrompt().contains("currentInventory"));
            assertTrue(request.userPrompt().contains("proposed"));
            assertFalse(request.userPrompt().contains("你来到石门前。"), "独立审阅不应重复发送上一回正文");
            return result("""
                    {"reference":{"passed":true,"problem":""},
                     "inventory":{"passed":true,"problem":""},
                     "authorization":{"passed":false,"problem":"正文擅自替玩家追问了古战场"},
                     "decision":{"passed":true,"problem":""},
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
        assertEquals(1, review.modelResults().size());
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
        StoryModelGateway gateway = request -> calls.incrementAndGet() == 1
                ? result("""
                        {"reference":{"passed":true,"problem":""},"inventory":{"passed":true,"problem":""},
                         "authorization":{"passed":true,"problem":""},"decision":{"passed":true,"problem":""},
                         "time":{"passed":true,"problem":""},"state":{"passed":true,"problem":""}}
                        """, 30, 10, 50)
                : result("""
                        {"chronologicalAgencyEvents":["到达第一处岔路","选择主路继续前行"],
                         "protagonistActions":["继续前行"],"protagonistDecisions":["选择主路"],
                         "protagonistMotives":[],"endingState":"第二处岔路",
                         "firstRequiredStopOccurrence":"第一处岔路","actionsAfterRequiredStop":["选择主路继续前行"],
                         "passed":true,"problem":""}
                        """, 20, 15, 45);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch proposed = mapper.readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.Review review = agent(gateway).review(context(), route(),
                new StoryTurnAgent.InvocationContext("save-bad-agency-pass", 10), proposed);

        assertFalse(review.accepted());
        assertTrue(review.requiresFreshNarrative());
        assertTrue(review.problem().contains("首次应停止位置之后"));
    }

    @Test
    void rejectsNarrativeThatInventsAnUnstatedPlayerDecision() throws Exception {
        StoryModelGateway gateway = request -> result("""
                {"reference":{"passed":true,"problem":""},
                 "inventory":{"passed":true,"problem":""},
                 "authorization":{"passed":true,"problem":""},
                 "decision":{"passed":false,"problem":"玩家只要求说明缘由并取回物品，正文却补成已经决定前往古战场"},
                 "time":{"passed":true,"problem":""},
                 "state":{"passed":true,"problem":""}}
                """, 42, 16, 95);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        StoryPatch proposed = mapper.readValue(VALID_PATCH, StoryPatch.class);

        StoryTurnAgent.Review review = agent(gateway).review(context(), route(),
                new StoryTurnAgent.InvocationContext("save-review-decision", 10), proposed);

        assertFalse(review.accepted());
        assertEquals("玩家决定：玩家只要求说明缘由并取回物品，正文却补成已经决定前往古战场", review.problem());
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

        assertTrue(error.getMessage().contains("缺少“玩家决定”检查结果"));
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

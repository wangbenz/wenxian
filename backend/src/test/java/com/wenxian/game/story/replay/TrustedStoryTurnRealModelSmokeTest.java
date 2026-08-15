package com.wenxian.game.story.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.LangChain4jStoryModelGateway;
import com.wenxian.game.story.agent.ModelBackedStoryTurnAgent;
import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextRequest;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextSelection;
import com.wenxian.game.story.agent.StoryModelRoute;
import com.wenxian.game.story.agent.StoryModelInvocationRecorder;
import com.wenxian.game.story.agent.StoryModelGateway;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryTurnAgent;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryPatch.WorldFactOperation;
import com.wenxian.game.story.domain.StoryPatchValidator;
import com.wenxian.game.story.domain.ExplicitDurationParser;
import com.wenxian.game.story.domain.StoryActionContractPatchValidator;
import com.wenxian.game.story.domain.StoryActionContractValidator;
import com.wenxian.game.story.domain.StoryEntityReferenceCompiler;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.SemanticDurationParser;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.TrustedStoryEventCompiler;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.domain.StoryProjection.WorldFactState;
import com.wenxian.game.story.reducer.StoryStateReducer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 配置 DEEPSEEK_API_KEY 后，使用真实模型回放可信叙事六类核心问题。 */
class TrustedStoryTurnRealModelSmokeTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final PromptContextAssembler assembler = new PromptContextAssembler();
    private final AtomicInteger entitySequence = new AtomicInteger();
    private final List<String> operations = new ArrayList<>();
    private final StoryStateReducer reducer = new StoryStateReducer(new StoryPatchValidator(),
            prefix -> prefix + "-real-smoke-" + entitySequence.incrementAndGet());
    private final StoryActionContractValidator actionContracts = new StoryActionContractValidator();
    private final StoryActionContractPatchValidator contractPatches = new StoryActionContractPatchValidator();
    private final StoryEntityReferenceCompiler referenceCompiler = new StoryEntityReferenceCompiler();
    private final TrustedStoryEventCompiler eventCompiler = new TrustedStoryEventCompiler();

    @Test
    void realModelPassesTrustedStoryReplayMatrix() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "set DEEPSEEK_API_KEY to run real trusted-story replay");
        String model = value(System.getenv("DEEPSEEK_MODEL"), "deepseek-v4-flash");
        StoryModelRoute route = new StoryModelRoute(
                value(System.getenv("DEEPSEEK_BASE_URL"), "https://api.deepseek.com"), key,
                "DEEPSEEK", "OPENAI_COMPATIBLE", model, "TEST_ENVIRONMENT");
        StoryModelInvocationRecorder recorder = new StoryModelInvocationRecorder() {
            @Override
            public void succeeded(StoryTurnAgent.InvocationContext invocation, String operation,
                                  StoryModelRoute modelRoute, StoryModelGateway.Result result) {
                operations.add(operation);
            }
        };
        StoryModelGateway realGateway = new LangChain4jStoryModelGateway();
        StoryModelGateway observedGateway = request -> {
            StoryModelGateway.Result result = realGateway.generate(request);
            if (request.systemPrompt().contains("回合结果规划 Agent")) {
                try {
                    StoryPatch planned = mapper.readValue(rawJson(result.text()), StoryPatch.class);
                    new StoryPatchValidator().validate(planned);
                    System.out.println("OUTCOME_PLAN_SHAPE valid=true");
                } catch (Exception malformed) {
                    System.out.println("OUTCOME_PLAN_SHAPE valid=false problem=" + malformed.getMessage());
                }
            } else if (request.systemPrompt().contains("结果计划演绎 Agent")) {
                try {
                    mapper.readTree(rawJson(result.text()));
                    System.out.println("NARRATIVE_RENDER_SHAPE valid=true");
                } catch (Exception malformed) {
                    System.out.println("NARRATIVE_RENDER_SHAPE valid=false problem=" + malformed.getMessage());
                }
            }
            return result;
        };
        StoryTurnAgent agent = new ModelBackedStoryTurnAgent(
                observedGateway, recorder, mapper, "deepseek-v4-flash");
        String selectedScenario = value(System.getenv("WENXIAN_REAL_MODEL_SCENARIO"), "all");

        if (selected(selectedScenario, "death")) deadCharacter(agent, route);
        if (selected(selectedScenario, "ability")) masteredAbility(agent, route);
        if (selected(selectedScenario, "item")) itemQuantity(agent, route);
        if (selected(selectedScenario, "time")) explicitTime(agent, route);
        if (selected(selectedScenario, "agency")) playerAgency(agent, route);
        if (selected(selectedScenario, "world")) worldExpansion(agent, route);
    }

    private void deadCharacter(StoryTurnAgent agent, StoryModelRoute route) {
        EntityState mentor = entity("character-mentor", "CHARACTER", "闻溪", null, null, null,
                LifecycleState.DEAD);
        var applied = run("death", "回到旧居寻找师父留下的线索。",
                projection(0, Map.of(mentor.id(), mentor), Map.of()), agent, route);
        assertEquals(LifecycleState.DEAD, applied.projection().entities().get(mentor.id()).lifecycle());
        assertEquals(1, applied.projection().entities().values().stream()
                .filter(value -> "闻溪".equals(value.name())).count());
    }

    private void masteredAbility(StoryTurnAgent agent, StoryModelRoute route) {
        EntityState ability = entity("ability-reflection", "ABILITY", "照影诀", null, null,
                AbilityState.MASTERED, null);
        var applied = run("ability", "施展照影诀查看石门后的禁制。",
                projection(0, Map.of(ability.id(), ability), Map.of()), agent, route);
        AbilityState state = applied.projection().entities().get(ability.id()).abilityState();
        assertTrue(state == AbilityState.MASTERED || state == AbilityState.TEMPORARILY_BLOCKED);
        assertEquals(1, applied.projection().entities().values().stream()
                .filter(value -> "照影诀".equals(value.name())).count());
    }

    private void itemQuantity(StoryTurnAgent agent, StoryModelRoute route) {
        EntityState pills = entity("item-clearing-pill", "ITEM", "清心丹",
                new QuantityValue(true, 3), true, null, null);
        EntityState materials = entity("item-unnumbered-treasure", "ITEM", "一包散碎灵材",
                new QuantityValue(false, null), true, null, null);
        var applied = run("item", "取出一枚清心丹服下，再检查那包散碎灵材。",
                projection(0, Map.of(pills.id(), pills, materials.id(), materials), Map.of()), agent, route);
        assertEquals(2, applied.projection().entities().get(pills.id()).quantity().value());
        assertFalse(applied.projection().entities().get(materials.id()).quantity().known());
    }

    private void explicitTime(StoryTurnAgent agent, StoryModelRoute route) {
        var applied = run("time", "闭关三年，专心温养神识；若没有外力打断，不要提前出关。",
                projection(12_000, Map.of(), Map.of()), agent, route);
        long elapsed = applied.acceptedPatch().time().elapsedMinutes();
        if (applied.acceptedPatch().time().resolution() == TimeResolution.COMPLETE) {
            assertEquals(1_555_200, elapsed);
        } else {
            assertTrue(elapsed < 1_555_200);
            assertFalse(applied.acceptedPatch().time().interruptionReason().isBlank());
        }
        assertEquals(12_000 + elapsed, applied.projection().worldTimeMinutes());
    }

    private void playerAgency(StoryTurnAgent agent, StoryModelRoute route) {
        WorldFactState fact = new WorldFactState("world-fact-sect-offer",
                "主角尚未加入宗门，眼前使者正在说明入门条件", 1, true,
                WorldFactOperation.EXPAND, "当前局势");
        var applied = run("agency", "问清加入宗门需要承担什么，也问问拒绝会有什么后果。",
                projection(0, Map.of(), Map.of(fact.id(), fact)), agent, route);
        assertEquals(fact.statement(), applied.projection().worldFacts().get(fact.id()).statement(),
                "询问条件不能被静默改成已经入宗");
        assertFalse(applied.acceptedPatch().narrative().contains("你已加入"));
        assertFalse(applied.acceptedPatch().choices().isEmpty(), "询问完成后应当把后续行动交还玩家");
    }

    private void worldExpansion(StoryTurnAgent agent, StoryModelRoute route) {
        WorldFactState fact = new WorldFactState("world-fact-ascension", "此界是否存在飞升之路尚无定论", 1,
                true, WorldFactOperation.EXPAND, "世界起点");
        var applied = run("world", "前往当地藏书阁，查找有关古代飞升传闻的公开记载，不预设调查结果。",
                projection(0, Map.of(), Map.of(fact.id(), fact)), agent, route);
        assertTrue(applied.projection().worldFacts().containsKey(fact.id()));
    }

    private StoryStateReducer.Applied run(String scenario, String action, StoryProjection projection,
                                          StoryTurnAgent agent, StoryModelRoute route) {
        int firstOperation = operations.size();
        ContextSelection selection = new ContextSelection(projection.entities().keySet(),
                projection.entities().keySet(), projection.worldFacts().keySet(), List.of(), Set.of());
        SemanticDuration semanticDuration = SemanticDurationParser.parse(action).orElse(null);
        Integer legacyDuration = semanticDuration == null
                ? ExplicitDurationParser.parseMinutes(action, projection.worldTimeMinutes()) : null;
        Long explicitDurationMinutes;
        if (semanticDuration != null) {
            explicitDurationMinutes = semanticDuration.toCompatibilityMinutes(projection.worldTimeMinutes());
        } else {
            explicitDurationMinutes = legacyDuration == null ? null : legacyDuration.longValue();
        }
        StoryPromptContext context = assembler.assemble(projection,
                new ContextRequest(action, List.of(), selection, "", null, null,
                        explicitDurationMinutes, null, semanticDuration));
        StoryTurnAgent.InvocationContext invocation = new StoryTurnAgent.InvocationContext(
                "real-smoke-" + scenario, 1);
        StoryTurnAgent.ResolvedAction resolved = agent.resolveAction(context, route, invocation);
        var contract = referenceCompiler.compile(projection, resolved.contract(), List.of(), explicitDurationMinutes);
        actionContracts.validate(projection, contract);
        context = context.withActionContract(contract);
        List<com.wenxian.game.story.agent.StoryModelGateway.Result> attempts = new ArrayList<>();
        attempts.addAll(resolved.modelResults());

        StoryTurnAgent.PlannedOutcome planned = agent.planOutcome(context, route, invocation);
        attempts.addAll(planned.modelResults());
        StoryPatch plan = eventCompiler.compile(projection, planned.patch(), semanticDuration).normalizedPatch();
        try {
            preflight(projection, contract, plan, explicitDurationMinutes);
        } catch (IllegalArgumentException rejectedPlan) {
            planned = agent.repairOutcomePlan(context, route, invocation, plan, rejectedPlan.getMessage());
            attempts.addAll(planned.modelResults());
            plan = eventCompiler.compile(projection, planned.patch(), semanticDuration).normalizedPatch();
            preflight(projection, contract, plan, explicitDurationMinutes);
        }

        StoryTurnAgent.RenderedNarrative rendered = agent.renderNarrative(context, route, invocation, plan);
        attempts.addAll(rendered.modelResults());
        StoryTurnAgent.Review review = agent.reviewOutcomeCoverage(context, route, invocation, plan, rendered);
        attempts.addAll(review.modelResults());
        if (!review.accepted()) {
            if (review.outcome() == StoryTurnAgent.ReviewOutcome.REPAIR_FACTS) {
                planned = agent.repairOutcomePlan(context, route, invocation, plan, review.problem());
                attempts.addAll(planned.modelResults());
                plan = eventCompiler.compile(projection, planned.patch(), semanticDuration).normalizedPatch();
                preflight(projection, contract, plan, explicitDurationMinutes);
                rendered = agent.renderNarrative(context, route, invocation, plan);
                attempts.addAll(rendered.modelResults());
            } else {
                rendered = agent.repairNarrative(context, route, invocation, plan, rendered, review.problem());
                attempts.addAll(rendered.modelResults());
            }
            review = agent.reviewOutcomeCoverage(context, route, invocation, plan, rendered);
            attempts.addAll(review.modelResults());
            assertTrue(review.accepted(), "一次自动整理后的结果覆盖审阅仍拒绝：" + review.problem());
        }

        StoryPatch finalPatch = compose(plan, rendered);
        contractPatches.validate(projection, contract, finalPatch);
        StoryStateReducer.Applied applied = reducer.reduce(projection, finalPatch, explicitDurationMinutes);
        StoryTurnAgent.Generated generated = new StoryTurnAgent.Generated(finalPatch, attempts);
        assertFalse(eventCompiler.compile(projection, applied.acceptedPatch(), semanticDuration).events().isEmpty(),
                "真实回合提交前必须形成至少一个可信事件");
        System.out.printf("TRUSTED_REAL_MODEL scenario=%s attempts=%d prompt=%d completion=%d latencyMs=%d model=%s operations=%s%n",
                scenario, generated.modelResults().size(), generated.inputTokens(), generated.outputTokens(),
                generated.latencyMs(), generated.finalResult().model(),
                operations.subList(firstOperation, operations.size()));
        return applied;
    }

    private void preflight(StoryProjection projection, com.wenxian.game.story.domain.StoryActionContract contract,
                           StoryPatch plan, Long explicitDurationMinutes) {
        contractPatches.validate(projection, contract, plan);
        reducer.reduce(projection, plan, explicitDurationMinutes);
    }

    private StoryPatch compose(StoryPatch plan, StoryTurnAgent.RenderedNarrative rendered) {
        return new StoryPatch(rendered.title(), rendered.narrative(), plan.time(), plan.coreState(), plan.entities(),
                plan.worldFacts(), plan.knowledge(), plan.memories(), plan.decisionCheckpoint(), rendered.choices(),
                rendered.rollingSummary());
    }

    private StoryProjection projection(long time, Map<String, EntityState> entities,
                                       Map<String, WorldFactState> facts) {
        return new StoryProjection(time, new CoreState("青岐州", "平静", "引气", "自在问道"),
                entities, facts, Map.of(), List.of());
    }

    private EntityState entity(String id, String type, String name, QuantityValue quantity,
                               Boolean possession, AbilityState ability, LifecycleState lifecycle) {
        return new EntityState(id, type, name, null, null, null, true,
                quantity, possession, ability, lifecycle);
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

    private String rawJson(String text) {
        String value = text == null ? "" : text.strip();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end >= start ? value.substring(start, end + 1) : value;
    }
}

package com.wenxian.game.story.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.LangChain4jStoryModelGateway;
import com.wenxian.game.story.agent.ModelBackedStoryTurnAgent;
import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextRequest;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextSelection;
import com.wenxian.game.story.agent.StoryModelRoute;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryTurnAgent;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryPatch.WorldFactOperation;
import com.wenxian.game.story.domain.StoryPatchValidator;
import com.wenxian.game.story.domain.ExplicitDurationParser;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.domain.StoryProjection.WorldFactState;
import com.wenxian.game.story.reducer.StoryStateReducer;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    private final StoryStateReducer reducer = new StoryStateReducer(new StoryPatchValidator(),
            prefix -> prefix + "-real-smoke-" + entitySequence.incrementAndGet());

    @Test
    void realModelPassesTrustedStoryReplayMatrix() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "set DEEPSEEK_API_KEY to run real trusted-story replay");
        String model = value(System.getenv("DEEPSEEK_MODEL"), "deepseek-v4-flash");
        StoryModelRoute route = new StoryModelRoute(
                value(System.getenv("DEEPSEEK_BASE_URL"), "https://api.deepseek.com"), key,
                "DEEPSEEK", "OPENAI_COMPATIBLE", model, "TEST_ENVIRONMENT");
        StoryTurnAgent agent = new ModelBackedStoryTurnAgent(new LangChain4jStoryModelGateway(), mapper);

        deadCharacter(agent, route);
        masteredAbility(agent, route);
        itemQuantity(agent, route);
        explicitTime(agent, route);
        playerAgency(agent, route);
        worldAmendment(agent, route);
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
        assertTrue(applied.acceptedPatch().decisionCheckpoint().required());
    }

    private void worldAmendment(StoryTurnAgent agent, StoryModelRoute route) {
        WorldFactState fact = new WorldFactState("world-fact-ascension", "此界无人能够飞升", 1,
                true, WorldFactOperation.EXPAND, "世界起点");
        var applied = run("world", "修订世界设定：飞升一直存在，只是道路被少数势力隐瞒。以后按这个设定推进。",
                projection(0, Map.of(), Map.of(fact.id(), fact)), agent, route);
        WorldFactState revised = applied.projection().worldFacts().get(fact.id());
        assertEquals(2, revised.revision());
        assertEquals(WorldFactOperation.REVISE, revised.lastOperation());
    }

    private StoryStateReducer.Applied run(String scenario, String action, StoryProjection projection,
                                          StoryTurnAgent agent, StoryModelRoute route) {
        ContextSelection selection = new ContextSelection(projection.entities().keySet(),
                projection.entities().keySet(), projection.worldFacts().keySet(), List.of(), Set.of());
        StoryPromptContext context = assembler.assemble(projection,
                new ContextRequest(action, List.of(), selection, "", null));
        StoryTurnAgent.InvocationContext invocation = new StoryTurnAgent.InvocationContext(
                "real-smoke-" + scenario, 1);
        StoryTurnAgent.Generated generated = agent.generate(context, route, invocation);
        Integer explicitDuration = ExplicitDurationParser.parseMinutes(action, projection.worldTimeMinutes());
        Long explicitDurationMinutes = explicitDuration == null ? null : explicitDuration.longValue();
        StoryStateReducer.Applied applied;
        try {
            applied = reducer.reduce(projection, generated.patch(), explicitDurationMinutes);
        } catch (IllegalArgumentException rejected) {
            StoryTurnAgent.Generated repaired = agent.repairRejected(context, route, invocation,
                    generated.patch(), rejected.getMessage());
            generated = new StoryTurnAgent.Generated(repaired.patch(), java.util.stream.Stream
                    .concat(generated.modelResults().stream(), repaired.modelResults().stream()).toList());
            applied = reducer.reduce(projection, repaired.patch(), explicitDurationMinutes);
        }
        System.out.printf("TRUSTED_REAL_MODEL scenario=%s attempts=%d prompt=%d completion=%d latencyMs=%d model=%s%n",
                scenario, generated.modelResults().size(), generated.inputTokens(), generated.outputTokens(),
                generated.latencyMs(), generated.finalResult().model());
        return applied;
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
}

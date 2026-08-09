package com.wenxian.game.story.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.ModelBackedStoryTurnAgent;
import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextRequest;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextSelection;
import com.wenxian.game.story.agent.StoryModelGateway;
import com.wenxian.game.story.agent.StoryModelRoute;
import com.wenxian.game.story.agent.StoryTurnAgent;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.DecisionCheckpoint;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.TimePassage;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryPatch.WorldFactOperation;
import com.wenxian.game.story.domain.StoryPatch.WorldFactPatch;
import com.wenxian.game.story.domain.StoryPatchValidator;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.domain.StoryProjection.WorldFactState;
import com.wenxian.game.story.reducer.StoryStateReducer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 将六类匿名玩家问题贯穿“分层上下文→模型提案→Reducer”主链路。
 *
 * <p>模型在此使用确定性响应，验证的是 Java 事实边界，而不是宣称真实模型永远不会写错正文。真实模型
 * 顺从性另由可选冒烟测试负责；只有两层都通过，普通回合才允许切换到新运行时。</p>
 */
class TrustedStoryTurnPipelineTest {
    private static final String FIXTURE = "/story/trusted-story-replay-cases.json";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final PromptContextAssembler contexts = new PromptContextAssembler();
    private final StoryStateReducer reducer = new StoryStateReducer(
            new StoryPatchValidator(), prefix -> prefix + "-accepted");

    @Test
    void preservesDeadCharacterInsteadOfCreatingAReplacement() throws Exception {
        EntityState mentor = entity("character-mentor", "CHARACTER", "闻溪", null, null, null,
                LifecycleState.DEAD);
        StoryProjection before = projection(0, Map.of(mentor.id(), mentor), Map.of());

        StoryProjection after = execute("character-death-no-silent-revival", before,
                patch(30, List.of(), List.of(), checkpoint(false, null))).projection();

        assertEquals(1, after.entities().size());
        assertEquals(LifecycleState.DEAD, after.entities().get(mentor.id()).lifecycle());
    }

    @Test
    void keepsMasteredAbilityAuthoritative() throws Exception {
        EntityState ability = entity("ability-reflection", "ABILITY", "照影诀", null, null,
                AbilityState.MASTERED, null);
        StoryProjection before = projection(0, Map.of(ability.id(), ability), Map.of());
        EntityPatch used = entityPatch(EntityOperation.UPDATE, ability.id(), "ABILITY", null,
                "刚刚施展", null, null, AbilityState.MASTERED, null);

        StoryProjection after = execute("mastered-ability-remains-usable", before,
                patch(20, List.of(used), List.of(), checkpoint(false, null))).projection();

        assertEquals(1, after.entities().size());
        assertEquals(AbilityState.MASTERED, after.entities().get(ability.id()).abilityState());
    }

    @Test
    void conservesKnownQuantityAndDoesNotInventUnknownQuantity() throws Exception {
        EntityState pills = entity("item-clearing-pill", "ITEM", "清心丹",
                new QuantityValue(true, 3), true, null, null);
        EntityState materials = entity("item-unnumbered-treasure", "ITEM", "一包散碎灵材",
                new QuantityValue(false, null), true, null, null);
        StoryProjection before = projection(0, Map.of(pills.id(), pills, materials.id(), materials), Map.of());
        EntityPatch consume = entityPatch(EntityOperation.UPDATE, pills.id(), "ITEM", null,
                "剩余两枚", new QuantityValue(true, 2), true, null, null);
        EntityPatch inspect = entityPatch(EntityOperation.UPDATE, materials.id(), "ITEM", null,
                "仍未清点", new QuantityValue(false, null), true, null, null);

        StoryProjection after = execute("known-item-quantity-is-conserved", before,
                patch(10, List.of(consume, inspect), List.of(), checkpoint(false, null))).projection();

        assertEquals(2, after.entities().get(pills.id()).quantity().value());
        assertFalse(after.entities().get(materials.id()).quantity().known());
        assertEquals(null, after.entities().get(materials.id()).quantity().value());
    }

    @Test
    void advancesTheExplicitThreeYearDuration() throws Exception {
        StoryProjection before = projection(12_000, Map.of(), Map.of());

        StoryStateReducer.Applied applied = execute("explicit-time-duration-is-respected", before,
                patch(1_555_200, List.of(), List.of(), checkpoint(false, null)));

        assertEquals(1_567_200, applied.projection().worldTimeMinutes());
        assertEquals(1_555_200, applied.acceptedPatch().time().elapsedMinutes());
        assertEquals(TimeResolution.COMPLETE, applied.acceptedPatch().time().resolution());
    }

    @Test
    void stopsBeforeTheUnapprovedMajorDecision() throws Exception {
        StoryProjection before = projection(0, Map.of(), Map.of());

        StoryPatch accepted = execute("major-decision-remains-with-player", before,
                patch(15, List.of(), List.of(), checkpoint(true, "是否接受使者提出的入门条件？")))
                .acceptedPatch();

        assertTrue(accepted.decisionCheckpoint().required());
        assertTrue(accepted.decisionCheckpoint().prompt().contains("是否"));
    }

    @Test
    void revisesWorldFactWithoutErasingItsIdentity() throws Exception {
        WorldFactState fact = new WorldFactState("world-fact-ascension", "此界无人能够飞升", 1,
                true, WorldFactOperation.EXPAND, "世界起点");
        StoryProjection before = projection(0, Map.of(), Map.of(fact.id(), fact));
        WorldFactPatch revise = new WorldFactPatch(WorldFactOperation.REVISE, fact.id(),
                "飞升一直存在，只是道路被少数势力隐瞒", "玩家明确修订世界设定");

        StoryProjection after = execute("world-revision-is-explicit-and-audited", before,
                patch(0, List.of(), List.of(revise), checkpoint(false, null))).projection();

        WorldFactState current = after.worldFacts().get(fact.id());
        assertEquals(2, current.revision());
        assertEquals("玩家明确修订世界设定", current.lastReason());
        assertEquals(1, after.worldFacts().size());
    }

    private StoryStateReducer.Applied execute(String caseId, StoryProjection before, StoryPatch modelPatch)
            throws Exception {
        String action = action(caseId);
        ContextSelection selection = new ContextSelection(before.entities().keySet(), before.entities().keySet(),
                before.worldFacts().keySet(), List.of(), Set.of());
        var context = contexts.assemble(before,
                new ContextRequest(action, List.of(), selection, "", null));
        String response = mapper.writeValueAsString(modelPatch);
        StoryModelGateway gateway = request -> {
            assertTrue(request.userPrompt().contains(action));
            return new StoryModelGateway.Result(response, "response-" + caseId,
                    "deepseek-v4-flash", 100, 200, 500);
        };
        StoryTurnAgent agent = new ModelBackedStoryTurnAgent(gateway, mapper);
        StoryPatch proposal = agent.generate(context, route()).patch();
        return reducer.reduce(before, proposal);
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

    private EntityPatch entityPatch(EntityOperation operation, String id, String type, String name,
                                    String status, QuantityValue quantity, Boolean possession,
                                    AbilityState ability, StoryPatch.LifecycleValue lifecycle) {
        return new EntityPatch(operation, id, type, name, status, null, null,
                quantity, possession, ability, lifecycle);
    }

    private StoryPatch patch(long elapsedMinutes, List<EntityPatch> entities,
                             List<WorldFactPatch> worldFacts, DecisionCheckpoint checkpoint) {
        return new StoryPatch("回放验证", "你完成了本轮已经授权的行动，并停在新的决定之前。",
                new TimePassage(elapsedMinutes, TimeResolution.COMPLETE, null), null,
                entities, worldFacts, List.of(), List.of(), checkpoint,
                List.of(new StoryPatch.ChoiceDraft("继续观察", "先确认当前变化。")), "");
    }

    private DecisionCheckpoint checkpoint(boolean required, String prompt) {
        return new DecisionCheckpoint(required, prompt);
    }

    private StoryModelRoute route() {
        return new StoryModelRoute("https://example.invalid", "test-secret-key", "DEEPSEEK",
                "OPENAI_COMPATIBLE", "deepseek-v4-flash", "PUBLIC");
    }

    private String action(String caseId) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(FIXTURE)) {
            JsonNode root = mapper.readTree(stream);
            for (JsonNode replay : root.path("cases")) {
                if (caseId.equals(replay.path("id").asText())) return replay.path("action").asText();
            }
        }
        throw new IllegalArgumentException("没有找到回放案例：" + caseId);
    }
}

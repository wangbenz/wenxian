package com.wenxian.game.story.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.LifecycleValue;
import com.wenxian.game.story.domain.StoryPatch.RelationshipValue;
import com.wenxian.game.story.domain.StoryPatch.TimePassage;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedStoryEventCompilerTest {
    private final TrustedStoryEventCompiler compiler = new TrustedStoryEventCompiler();

    @Test
    void removesCrossTypeOptionalFieldsAndKeepsMeaningfulCharacterEvents() {
        EntityPatch character = new EntityPatch(EntityOperation.CREATE, null, "CHARACTER", "沈青禾",
                "仍在宗门", "师姐沈青禾已经进入长期记录", new ObjectMapper().createObjectNode(),
                new StoryPatch.QuantityValue(true, 1), true,
                new CustodyValue(HolderType.PLAYER, null), AbilityState.MASTERED,
                new LifecycleValue(LifecycleState.ACTIVE, "本轮相认"),
                new RelationshipValue("师姐", "本轮确认身份"), null, "r2");
        StoryPatch patch = patch(scene(), List.of(character));

        var compiled = compiler.compile(projection(), patch, null);
        EntityPatch normalized = compiled.normalizedPatch().entities().getFirst();

        assertNull(normalized.quantity());
        assertNull(normalized.inPossession());
        assertNull(normalized.custody());
        assertNull(normalized.abilityState());
        assertEquals(2, compiled.events().stream()
                .filter(event -> event instanceof TrustedStoryEvent.CharacterChanged).count());
    }

    @Test
    void createsSemanticTimeEventFromAuthorizedDuration() {
        SemanticDuration planned = new SemanticDuration(SemanticDuration.Unit.MONTH, 3, "");
        StoryPatch patch = patch(new TimePassage(planned, TimeResolution.COMPLETE, null), List.of());

        var compiled = compiler.compile(projection(), patch, planned);
        TrustedStoryEvent.TimeElapsed time = compiled.events().stream()
                .filter(TrustedStoryEvent.TimeElapsed.class::isInstance)
                .map(TrustedStoryEvent.TimeElapsed.class::cast).findFirst().orElseThrow();

        assertEquals(planned, time.planned());
        assertEquals(planned, time.actual());
        assertTrue(compiled.events().stream().anyMatch(TrustedStoryEvent.ActionResolved.class::isInstance));
    }

    @Test
    void inheritsFinalSceneForCharacterWhoActuallyAppears() {
        ObjectNode attributes = new ObjectMapper().createObjectNode().put("scenePresence", "PRESENT");
        EntityPatch character = character(EntityOperation.CREATE, null, "周伯", attributes);
        StoryPatch patch = new StoryPatch("会面", "你在灵草会馆与周伯当面交谈。",
                scene(),
                new StoryPatch.CoreStatePatch("灵草会馆", null, null, null), List.of(character),
                List.of(), List.of(), List.of(), null, List.of(), "你在灵草会馆见到周伯。",
                new StoryPatch.TurnOutcome(StoryPatch.OutcomeResolution.SUCCEEDED, "见到周伯"));

        EntityPatch normalized = compiler.compile(projection(), patch, null).normalizedPatch().entities().getFirst();

        assertEquals("灵草会馆", normalized.attributes().path("lastKnownLocation").asText());
        assertTrue(!normalized.attributes().has("scenePresence"));
    }

    @Test
    void keepsExplicitDifferentLocationAndDoesNotMoveMentionedCharacter() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode remote = mapper.createObjectNode()
                .put("scenePresence", "PRESENT").put("lastKnownLocation", "北山药庐");
        ObjectNode mentioned = mapper.createObjectNode().put("scenePresence", "MENTIONED");
        StoryPatch patch = new StoryPatch("传闻", "你在灵草会馆听人提到远方故人。",
                scene(),
                new StoryPatch.CoreStatePatch("灵草会馆", null, null, null),
                List.of(character(EntityOperation.CREATE, null, "药庐主人", remote),
                        character(EntityOperation.CREATE, null, "远方故人", mentioned)),
                List.of(), List.of(), List.of(), null, List.of(), "你听到两条消息。",
                new StoryPatch.TurnOutcome(StoryPatch.OutcomeResolution.SUCCEEDED, "获得消息"));

        List<EntityPatch> normalized = compiler.compile(projection(), patch, null).normalizedPatch().entities();

        assertEquals("北山药庐", normalized.get(0).attributes().path("lastKnownLocation").asText());
        assertNull(normalized.get(1).attributes());
    }

    @Test
    void projectsLightweightUpdateForExistingCharacterWithoutChangingOtherFacts() {
        ObjectNode attributes = new ObjectMapper().createObjectNode().put("scenePresence", "PRESENT");
        EntityPatch update = new EntityPatch(EntityOperation.UPDATE, "character-mentor", "CHARACTER",
                null, null, null, attributes, null, null, null, null, null, null, null, "");
        StoryPatch patch = new StoryPatch("再会", "你回到草庐，当面向师父复述消息。",
                scene(),
                new StoryPatch.CoreStatePatch("溪谷草庐", null, null, null), List.of(update),
                List.of(), List.of(), List.of(), null, List.of(), "你回到草庐见到师父。",
                new StoryPatch.TurnOutcome(StoryPatch.OutcomeResolution.SUCCEEDED, "复述消息"));

        EntityPatch normalized = compiler.compile(projectionWithMentor(), patch, null)
                .normalizedPatch().entities().getFirst();

        assertEquals("溪谷草庐", normalized.attributes().path("lastKnownLocation").asText());
        assertNull(normalized.name());
        assertNull(normalized.relationship());
        assertNull(normalized.lifecycle());
    }

    @Test
    void emitsTypedEventForAcceptedCultivationRankAdvance() {
        StoryProjection base = cultivationProjection();
        StoryProjection before = new StoryProjection(base.worldTime(), base.coreState(), base.entities(),
                base.worldFacts(), base.knowledge(), base.memories(), base.creationContract(),
                base.playPreference(), base.cultivationSystem(),
                new ProtagonistCultivation(base.cultivationSystem().systemId(), "stage-02",
                        WorldPackage.RealmPhase.PERFECT, 1, 2, "炼气圆满"));
        StoryPatch patch = new StoryPatch("筑基", "你在静室中筑成道基。", scene(), null,
                List.of(), List.of(), List.of(), List.of(), null, List.of(), "筑基已成。",
                new StoryPatch.TurnOutcome(StoryPatch.OutcomeResolution.SUCCEEDED, "筑基成功"),
                new StoryPatch.CultivationPatch("stage-03", WorldPackage.RealmPhase.EARLY,
                        0, 2, true, "道基初成", List.of("PLAYER_ACTION"),
                        StoryPatch.CultivationResolution.RANK_ADVANCED, "直接后继"));

        TrustedStoryEvent.CultivationChanged event = compiler.compile(before, patch, null).events().stream()
                .filter(TrustedStoryEvent.CultivationChanged.class::isInstance)
                .map(TrustedStoryEvent.CultivationChanged.class::cast).findFirst().orElseThrow();

        assertEquals(TrustedStoryEvent.EventType.CULTIVATION_RANK_ADVANCED, event.type());
        assertEquals("stage-02", event.fromStageId());
        assertEquals("stage-03", event.toStageId());
    }

    @Test
    void recordsProgressWithoutPretendingRankAdvanced() {
        StoryProjection before = cultivationProjection();
        StoryPatch patch = new StoryPatch("筑基未成", "你触及门槛后稳住了气息。", scene(), null,
                List.of(), List.of(), List.of(), List.of(), null, List.of(), "已经触及筑基门槛。",
                new StoryPatch.TurnOutcome(StoryPatch.OutcomeResolution.PARTIAL, "只形成部分进展"),
                new StoryPatch.CultivationPatch("stage-02", WorldPackage.RealmPhase.EARLY,
                        1, 2, false, "已经形成一枚进展", List.of("PLAYER_ACTION"),
                        StoryPatch.CultivationResolution.PROGRESSED, "本轮接受一枚进展"));

        List<TrustedStoryEvent.CultivationChanged> events = compiler.compile(before, patch, null).events().stream()
                .filter(TrustedStoryEvent.CultivationChanged.class::isInstance)
                .map(TrustedStoryEvent.CultivationChanged.class::cast).toList();

        assertEquals(1, events.size());
        assertEquals(TrustedStoryEvent.EventType.CULTIVATION_PROGRESS_CHANGED, events.getFirst().type());
        assertEquals("stage-02", events.getFirst().fromStageId());
        assertEquals("stage-02", events.getFirst().toStageId());
    }

    private EntityPatch character(EntityOperation operation, String id, String name, ObjectNode attributes) {
        return new EntityPatch(operation, id, "CHARACTER", name, "仍在活动", "本轮形成长期人物记录",
                attributes, null, null, null, null,
                new LifecycleValue(LifecycleState.ACTIVE, null),
                new RelationshipValue("相识", null), null, "");
    }

    private StoryPatch patch(TimePassage time, List<EntityPatch> entities) {
        return new StoryPatch("本轮", "你完成本轮行动并停下。", time, null, entities,
                List.of(), List.of(), List.of(), null, List.of(), "本轮已完成。");
    }

    private StoryProjection projection() {
        EntityState item = new EntityState("item-1", "ITEM", "青木药牌", "完好", "由玩家持有",
                null, true, new StoryPatch.QuantityValue(true, 1), true,
                new CustodyValue(HolderType.PLAYER, null), null, null);
        return new StoryProjection(StoryWorldTime.opening(), new CoreState("洞府", "平静", "炼气五层", "问仙"),
                Map.of(item.id(), item), Map.of(), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(), null, null);
    }

    private StoryProjection projectionWithMentor() {
        EntityState mentor = new EntityState("character-mentor", "CHARACTER", "陆玄川", "安好", "主角师父",
                null, true, null, null, null, null,
                LifecycleState.ACTIVE, new RelationshipValue("师父", null));
        return new StoryProjection(StoryWorldTime.opening(), new CoreState("灵草会馆", "平静", "炼气五层", "问仙"),
                Map.of(mentor.id(), mentor), Map.of(), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(), null, null);
    }

    private StoryProjection cultivationProjection() {
        CultivationSystem system = new CultivationSystem("cultivation-test", 1,
                "青冥炼真道", "借天地清气磨炼真形。", "人族", "stage-01", List.of(
                stage("stage-01", 1, "引气", "stage-02"),
                stage("stage-02", 2, "炼气", "stage-03"),
                stage("stage-03", 3, "筑基", null)
        ), new CultivationSystem.Confirmation(
                CultivationSystem.ConfirmationSource.PLAYER_CONFIRMED_RECOMMENDATION,
                Instant.parse("2026-08-20T12:00:00Z")));
        return new StoryProjection(StoryWorldTime.opening(),
                new CoreState("洞府", "平静", "炼气", "问仙"),
                Map.of(), Map.of(), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(),
                system, new ProtagonistCultivation(system.systemId(), "stage-02", "气息渐稳"));
    }

    private CultivationSystem.Stage stage(String id, int order, String name, String nextId) {
        return new CultivationSystem.Stage(id, order, name, name + "阶段。", List.of(),
                nextId == null ? List.of() : List.of(nextId));
    }

    private TimePassage scene() {
        return new TimePassage(new SemanticDuration(SemanticDuration.Unit.SCENE, 1, ""),
                TimeResolution.COMPLETE, null);
    }
}

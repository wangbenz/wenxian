package com.wenxian.game.story.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextRequest;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextSelection;
import com.wenxian.game.story.agent.PromptContextAssembler.KnowledgeRef;
import com.wenxian.game.story.agent.StoryPromptContext.PendingDecision;
import com.wenxian.game.story.agent.StoryPromptContext.PreviousTurn;
import com.wenxian.game.story.agent.StoryPromptContext.SelectedChoice;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.RelationshipValue;
import com.wenxian.game.story.domain.StoryPatch.WorldFactOperation;
import com.wenxian.game.story.domain.CultivationSystem;
import com.wenxian.game.story.domain.ProtagonistCultivation;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.domain.StoryProjection.KnowledgeState;
import com.wenxian.game.story.domain.StoryProjection.MemoryState;
import com.wenxian.game.story.domain.StoryProjection.WorldFactState;
import com.wenxian.game.story.domain.StoryWorldTime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptContextAssemblerTest {
    private final PromptContextAssembler assembler = new PromptContextAssembler();

    @Test
    void separatesObjectiveFactsCognitionMemoryNarrativeAndAction() {
        StoryProjection projection = projection();
        ContextSelection selection = new ContextSelection(
                Set.of("ability-reflection", "character-mentor", "item-sold-sword"),
                Set.of("ability-reflection"),
                Set.of("world-fact-ascension"),
                List.of(new KnowledgeRef("character-mentor", "world-fact-ascension")),
                Set.of("memory-promise")
        );
        PreviousTurn previous = new PreviousTurn(12, "询问师尊", "旧事重提",
                "师尊说他相信天门已经断绝。", "未改变客观世界事实");

        StoryPromptContext context = assembler.assemble(projection,
                new ContextRequest("施展照影诀查看天门旧痕", List.of("保持第二人称"), selection,
                        "你曾在山门立下承诺。", previous,
                        new PendingDecision("是否接受使者提出的入门条件？")));

        assertEquals("world-fact-ascension", context.objective().worldFacts().getFirst().id());
        assertEquals("世人尚不能证明天门是否存在",
                context.objective().worldFacts().getFirst().statement());
        assertEquals("师尊相信天门已经断绝", context.cognition().entries().getFirst().summary());
        assertEquals("memory-promise", context.memory().entries().getFirst().id());
        assertEquals(previous, context.narrative().previousTurn());
        assertEquals(List.of(previous), context.narrative().recentTurns());
        assertEquals("是否接受使者提出的入门条件？", context.objective().pendingDecision().prompt());
        assertEquals("施展照影诀查看天门旧痕", context.action().playerAction());
        assertEquals(null, context.action().semanticDuration());
        assertEquals(StoryWorldTime.opening(), context.objective().worldTime());
        assertEquals("测试根源", context.objective().creationContract().originSummary());
        assertTrue(context.objective().sourceExcerpts().stream()
                .anyMatch(value -> "creation:origin".equals(value.ref())));
        assertTrue(context.objective().sourceExcerpts().stream()
                .anyMatch(value -> "turn:12".equals(value.ref())));
        assertTrue(context.system().invariants().stream().anyMatch(value -> value.contains("重大不可逆决定")));
        assertTrue(context.system().invariants().stream().anyMatch(value -> value.contains("长期目标")));
        assertTrue(context.objective().currentInventory().complete());
        assertEquals(List.of("item-herbs"), context.objective().currentInventory().items().stream()
                .map(StoryPromptContext.EntityIndexEntry::id).toList());
        assertEquals(List.of("item-sold-sword"), context.objective().currentInventory().historicalItems().stream()
                .map(StoryPromptContext.EntityIndexEntry::id).toList());
        assertEquals("cultivation-test", context.objective().cultivation().systemId());
        assertEquals("stage-02", context.objective().cultivation().currentStageId());
        assertEquals("炼气", context.objective().cultivation().currentStageName());
        assertEquals(List.of("stage-01", "stage-02", "stage-03", "stage-04", "stage-05", "stage-06"),
                context.objective().cultivation().stages().stream()
                        .map(StoryPromptContext.CultivationStageView::stageId).toList());
        assertEquals(List.of("stage-03"), context.objective().cultivation().stages().get(1).allowedNextStageIds());
    }

    @Test
    void carriesMultipleRecentTurnsInChronologicalOrder() {
        PreviousTurn first = new PreviousTurn(10, "进入山门", "山门", "你抵达山门。", "抵达山门");
        PreviousTurn second = new PreviousTurn(11, "询问守门人", "问路", "守门人指向东院。", "得知东院方向");

        StoryPromptContext context = assembler.assemble(projection(), new ContextRequest(
                "前往东院", List.of(), ContextSelection.empty(), "你已经来到宗门。", second,
                null, null, null, List.of(first, second)));

        assertEquals(second, context.narrative().previousTurn());
        assertEquals(List.of(10L, 11L), context.narrative().recentTurns().stream()
                .map(PreviousTurn::sequence).toList());
    }

    @Test
    void carriesSelectedChoiceAsTraceableActionSource() {
        SelectedChoice choice = new SelectedChoice("choice-zhou", "询问老周", "向驿站里的老周打听前路。");
        StoryPromptContext context = assembler.assemble(projection(), new ContextRequest(
                "询问老周：向驿站里的老周打听前路。", List.of(), ContextSelection.empty(), "", null,
                null, choice));

        assertEquals(choice, context.action().selectedChoice());
        assertEquals("choice:choice-zhou", context.objective().sourceExcerpts().stream()
                .filter(value -> "SELECTED_CHOICE".equals(value.sourceType()))
                .findFirst().orElseThrow().ref());
    }

    @Test
    void exposesServerAuthorizedSemanticDuration() {
        var duration = new com.wenxian.game.story.domain.SemanticDuration(
                com.wenxian.game.story.domain.SemanticDuration.Unit.DAY, 1, "");
        StoryPromptContext context = assembler.assemble(projection(), new ContextRequest(
                "休息到第二天", List.of(), ContextSelection.empty(), "", null, null, null, duration));

        assertEquals(duration, context.action().semanticDuration());
    }

    @Test
    void onlyLoadsStableReferencesAndDoesNotGuessByNamesInActionText() {
        StoryPromptContext context = assembler.assemble(projection(), new ContextRequest(
                "找旧日师尊谈谈，再检查那包灵材",
                List.of(),
                new ContextSelection(Set.of("ability-reflection"), Set.of(), Set.of(), List.of(), Set.of()),
                "", null));

        assertEquals(List.of("ability-reflection"),
                context.objective().entityIndex().stream().map(StoryPromptContext.EntityIndexEntry::id).toList());
        assertTrue(context.objective().relatedEntities().isEmpty());
        assertFalse(context.objective().entityIndex().stream()
                .anyMatch(value -> "character-mentor".equals(value.id()) || "item-herbs".equals(value.id())));
    }

    @Test
    void explicitlySelectedHiddenHistoryCanBeLoadedWithoutRestoringIt() {
        StoryPromptContext context = assembler.assemble(projection(), new ContextRequest(
                "追查已经卖出的旧剑去向", List.of(),
                new ContextSelection(Set.of(), Set.of("item-sold-sword"), Set.of(), List.of(), Set.of()),
                "", null));

        assertEquals("item-sold-sword", context.objective().relatedEntities().getFirst().id());
        assertFalse(context.objective().relatedEntities().getFirst().visible());
        assertFalse(context.objective().relatedEntities().getFirst().inPossession());
        assertFalse(context.objective().currentInventory().items().stream()
                .anyMatch(value -> "item-sold-sword".equals(value.id())));
        assertTrue(context.objective().currentInventory().historicalItems().stream()
                .anyMatch(value -> "item-sold-sword".equals(value.id())));
    }

    @Test
    void rejectsStaleReferencesInsteadOfFallingBackToAnotherEntity() {
        ContextRequest request = new ContextRequest("施展旧术", List.of(),
                new ContextSelection(Set.of(), Set.of("ability-missing"), Set.of(), List.of(), Set.of()),
                "", null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> assembler.assemble(projection(), request));

        assertEquals("上下文引用的实体不存在：ability-missing", error.getMessage());
    }

    @Test
    void keepsLongNarrativeMaterialWithoutApplyingAHiddenTokenCutoff() {
        String summary = "旧事".repeat(4_000);
        String narrative = "上一回合正文".repeat(3_000);
        PreviousTurn previous = new PreviousTurn(88, "继续赶路", "长路",
                narrative, "只记录已经接受的变化");

        StoryPromptContext context = assembler.assemble(projection(), new ContextRequest(
                "停下休息", List.of(), ContextSelection.empty(), summary, previous));

        assertEquals(summary, context.narrative().rollingSummary());
        assertEquals(narrative, context.narrative().previousTurn().narrative());
    }

    @Test
    void producesDeterministicEntityOrderForPromptCaching() {
        StoryPromptContext context = assembler.assemble(projection(), new ContextRequest(
                "整理当前线索", List.of(),
                new ContextSelection(Set.of("item-herbs", "ability-reflection", "character-mentor"),
                        Set.of("character-mentor", "ability-reflection"), Set.of(), List.of(), Set.of()),
                "", null));

        assertEquals(List.of("ability-reflection", "character-mentor", "item-herbs"),
                context.objective().entityIndex().stream().map(StoryPromptContext.EntityIndexEntry::id).toList());
        assertEquals(List.of("ability-reflection", "character-mentor"),
                context.objective().relatedEntities().stream().map(StoryPromptContext.EntityDetail::id).toList());
    }

    @Test
    void characterObjectiveContextExcludesLegacyCustodyNarrative() {
        StoryPromptContext context = assembler.assemble(projection(), new ContextRequest(
                "与旧日师尊交谈", List.of(),
                new ContextSelection(Set.of("character-mentor"), Set.of("character-mentor"),
                        Set.of(), List.of(), Set.of()), "", null));

        StoryPromptContext.EntityIndexEntry index = context.objective().entityIndex().getFirst();
        StoryPromptContext.EntityDetail detail = context.objective().relatedEntities().getFirst();
        assertEquals("DEAD", index.status());
        assertEquals("DEAD", detail.status());
        assertFalse(detail.summary().contains("断剑尖"));
        assertTrue(detail.summary().contains("旧宗门长老"));
        assertTrue(detail.summary().contains("青石镇"));
        assertTrue(detail.summary().contains("旧识"));
    }

    private StoryProjection projection() {
        EntityState ability = new EntityState("ability-reflection", "ABILITY", "照影诀", "仍可施展",
                "可以映照旧痕", null, true, null, null, AbilityState.MASTERED, null);
        var mentorAttributes = JsonNodeFactory.instance.objectNode();
        mentorAttributes.put("identity", "旧宗门长老");
        mentorAttributes.put("lastKnownLocation", "青石镇");
        EntityState mentor = new EntityState("character-mentor", "CHARACTER", "旧日师尊",
                "已经身故；生前替玩家保管断剑尖", "只留下残缺手札并保管断剑尖",
                mentorAttributes, true, null, null, null, null, LifecycleState.DEAD,
                new RelationshipValue("旧识", "曾经交谈"));
        EntityState herbs = new EntityState("item-herbs", "ITEM", "一包灵材", "尚未清点",
                "来源不明", JsonNodeFactory.instance.objectNode().put("source", "旧矿洞"), true,
                new QuantityValue(false, null), true, null, null);
        EntityState soldSword = new EntityState("item-sold-sword", "ITEM", "青锋旧剑", "已经售出",
                "曾经随身多年", null, false, new QuantityValue(true, 0), false, null, null);
        WorldFactState worldFact = new WorldFactState("world-fact-ascension",
                "世人尚不能证明天门是否存在", 2, true, WorldFactOperation.TRANSFORM, "百年前天象变化");
        KnowledgeState belief = new KnowledgeState("character-mentor", "world-fact-ascension",
                "师尊相信天门已经断绝", List.of("turn-8"));
        MemoryState memory = new MemoryState("memory-promise", "PROMISE", "山门之诺",
                "你答应替师尊查清天门真相", "KEY", List.of("turn-9"));

        CultivationSystem cultivationSystem = new CultivationSystem("cultivation-test", 1,
                "青冥炼真道", "借天地清气磨炼真形。", "灵族", "stage-01", List.of(
                stage("stage-01", 1, "引气", "stage-02"),
                stage("stage-02", 2, "炼气", "stage-03"),
                stage("stage-03", 3, "筑基", "stage-04"),
                stage("stage-04", 4, "金丹", "stage-05"),
                stage("stage-05", 5, "元婴", "stage-06"),
                stage("stage-06", 6, "化神", null)
        ), new CultivationSystem.Confirmation(
                CultivationSystem.ConfirmationSource.PLAYER_CONFIRMED_RECOMMENDATION,
                Instant.parse("2026-08-20T12:00:00Z")));
        return new StoryProjection(StoryWorldTime.opening(), new CoreState("青石镇", "轻伤", "炼气", "查清天门"),
                Map.of(ability.id(), ability, mentor.id(), mentor, herbs.id(), herbs,
                        soldSword.id(), soldSword),
                Map.of(worldFact.id(), worldFact),
                Map.of("mentor-belief", belief),
                List.of(memory),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(),
                cultivationSystem, new ProtagonistCultivation("cultivation-test", "stage-02", "气息渐稳"));
    }

    private CultivationSystem.Stage stage(String id, int order, String name, String nextId) {
        return new CultivationSystem.Stage(id, order, name, name + "阶段。", List.of(),
                nextId == null ? List.of() : List.of(nextId));
    }
}

package com.wenxian.game.story.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.LangChain4jStoryModelGateway;
import com.wenxian.game.story.agent.ModelBackedStoryTurnAgent;
import com.wenxian.game.story.agent.StoryModelRoute;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryTurnAgent;
import com.wenxian.game.story.domain.StoryActionContractValidator;
import com.wenxian.game.story.domain.StoryActionPreconditionException;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.RelationshipValue;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用真实 Flash 验证“有效赠送”和“未发生赠送却要求取回”能形成不同事实契约。 */
class TrustedStoryActionContractRealModelTest {

    @Test
    void realFlashSeparatesValidGiftFromFalseTakeBackPremise() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "set DEEPSEEK_API_KEY to run action contract smoke");
        StoryModelRoute route = new StoryModelRoute(
                value(System.getenv("DEEPSEEK_BASE_URL"), "https://api.deepseek.com"), key,
                "DEEPSEEK", "OPENAI_COMPATIBLE", "deepseek-v4-flash", "TEST_ENVIRONMENT");
        StoryTurnAgent agent = new ModelBackedStoryTurnAgent(new LangChain4jStoryModelGateway(),
                new ObjectMapper().findAndRegisterModules());
        StoryProjection projection = projection();
        StoryActionContractValidator validator = new StoryActionContractValidator();

        StoryTurnAgent.ResolvedAction gift = agent.resolveAction(context("把断剑尖交给老妇保管，然后停下。"), route,
                new StoryTurnAgent.InvocationContext("contract-valid-gift", 1));
        assertDoesNotThrow(() -> validator.validate(projection, gift.contract()));
        print("gift", gift);

        StoryTurnAgent.ResolvedAction falseTakeBack = agent.resolveAction(
                context("把我之前交给老妇的断剑尖拿回来，然后停下。"), route,
                new StoryTurnAgent.InvocationContext("contract-false-takeback", 1));
        assertThrows(StoryActionPreconditionException.class,
                () -> validator.validate(projection, falseTakeBack.contract()));
        print("false-takeback", falseTakeBack);

        StoryProjection heldByOldWoman = projectionHeldByOldWoman();
        StoryTurnAgent.ResolvedAction validTakeBack = agent.resolveAction(
                contextHeldByOldWoman("请老妇把她正在保管的断剑尖还给我，放回我的行囊后停下。"), route,
                new StoryTurnAgent.InvocationContext("contract-valid-takeback", 1));
        assertDoesNotThrow(() -> validator.validate(heldByOldWoman, validTakeBack.contract()));
        assertEquals(HolderType.CHARACTER,
                validTakeBack.contract().references().stream()
                        .filter(reference -> "ITEM".equals(reference.entityType()))
                        .findFirst().orElseThrow().expectedCustody().holderType());
        assertEquals(0, validTakeBack.contract().references().stream()
                .filter(reference -> reference.resolution()
                        == com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution.NEW)
                .count(), "行囊只是 PLAYER 保管关系，不应被创建为新物品");
        print("valid-takeback", validTakeBack);

        StoryTurnAgent.ResolvedAction falseRelationship = agent.resolveAction(
                context("既然老妇是我多年的师父，就请她继续传授我本门功法。"), route,
                new StoryTurnAgent.InvocationContext("contract-false-relationship", 1));
        assertThrows(StoryActionPreconditionException.class,
                () -> validator.validate(projection, falseRelationship.contract()));
        print("false-relationship", falseRelationship);

        StoryTurnAgent.ResolvedAction newTile = agent.resolveAction(
                context("从脚边拾起一片刚掉落的青色瓦片碎片，确认普通后放入行囊。"), route,
                new StoryTurnAgent.InvocationContext("contract-new-tile", 1));
        assertDoesNotThrow(() -> validator.validate(projection, newTile.contract()));
        assertEquals(com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution.NEW,
                newTile.contract().references().getFirst().resolution());
        print("new-tile", newTile);

        StoryTurnAgent.ResolvedAction missingTile = agent.resolveAction(
                context("从我的行囊里取出青色瓦片碎片继续观察。"), route,
                new StoryTurnAgent.InvocationContext("contract-missing-tile", 1));
        assertThrows(StoryActionPreconditionException.class,
                () -> validator.validate(projection, missingTile.contract()));
        print("missing-tile", missingTile);

        StoryTurnAgent.ResolvedAction observeWell = agent.resolveAction(
                context("我只观察村中水井是否安全，不打水、不下井，也不离开井边。"), route,
                new StoryTurnAgent.InvocationContext("contract-landmark-not-item", 1));
        assertDoesNotThrow(() -> validator.validate(projection, observeWell.contract()));
        assertFalse(observeWell.contract().itemActionDetected());
        assertEquals(0, observeWell.contract().references().stream()
                .filter(reference -> "ITEM".equalsIgnoreCase(reference.entityType())).count(),
                "水井等场景地标不得被解析为背包物品");
        print("landmark-not-item", observeWell);
    }

    private StoryProjection projection() {
        EntityState item = new EntityState("item-sword-tip", "ITEM", "断剑尖", "仍在行囊", "一截断剑",
                null, true, new QuantityValue(true, 1), true,
                new CustodyValue(HolderType.PLAYER, null), null, null);
        EntityState character = new EntityState("character-old-woman", "CHARACTER", "老妇", "在村口歇息",
                "曾与主角交谈", null, true, null, null, null, null, LifecycleState.ACTIVE,
                new RelationshipValue("旧识", "曾在村口交谈"));
        return new StoryProjection(600, new CoreState("村口", "平静", "凡俗", "问道"),
                Map.of(item.id(), item, character.id(), character), Map.of(), Map.of(), List.of());
    }

    private StoryProjection projectionHeldByOldWoman() {
        EntityState item = new EntityState("item-sword-tip", "ITEM", "断剑尖", "由老妇保管", "一截断剑",
                null, true, new QuantityValue(true, 1), false,
                new CustodyValue(HolderType.CHARACTER, "character-old-woman"), null, null);
        EntityState character = new EntityState("character-old-woman", "CHARACTER", "老妇", "在村口歇息",
                "曾与主角交谈", null, true, null, null, null, null, LifecycleState.ACTIVE,
                new RelationshipValue("旧识", "曾在村口交谈"));
        return new StoryProjection(600, new CoreState("村口", "平静", "凡俗", "问道"),
                Map.of(item.id(), item, character.id(), character), Map.of(), Map.of(), List.of());
    }

    private StoryPromptContext context(String action) {
        StoryProjection projection = projection();
        var item = new StoryPromptContext.EntityIndexEntry("item-sword-tip", "ITEM", "断剑尖", "仍在行囊",
                true, new QuantityValue(true, 1), true, new CustodyValue(HolderType.PLAYER, null), null, null);
        var character = new StoryPromptContext.EntityIndexEntry("character-old-woman", "CHARACTER", "老妇",
                "在村口歇息", true, null, null, null, null, LifecycleState.ACTIVE,
                new RelationshipValue("旧识", "曾在村口交谈"));
        return new StoryPromptContext(new StoryPromptContext.SystemContext(List.of(), List.of()),
                new StoryPromptContext.ObjectiveContext(projection.worldTimeMinutes(),
                        StoryPromptContext.WorldClock.from(projection.worldTimeMinutes()), projection.coreState(),
                        List.of(), List.of(item, character), List.of(),
                        new StoryPromptContext.InventoryView(true, List.of(item), List.of()), null),
                new StoryPromptContext.CognitionContext(List.of()), new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("", null), new StoryPromptContext.ActionContext(action));
    }

    private StoryPromptContext contextHeldByOldWoman(String action) {
        StoryProjection projection = projectionHeldByOldWoman();
        var item = new StoryPromptContext.EntityIndexEntry("item-sword-tip", "ITEM", "断剑尖", "由老妇保管",
                true, new QuantityValue(true, 1), false,
                new CustodyValue(HolderType.CHARACTER, "character-old-woman"), null, null);
        var character = new StoryPromptContext.EntityIndexEntry("character-old-woman", "CHARACTER", "老妇",
                "在村口歇息", true, null, null, null, null, LifecycleState.ACTIVE,
                new RelationshipValue("旧识", "曾在村口交谈"));
        return new StoryPromptContext(new StoryPromptContext.SystemContext(List.of(), List.of()),
                new StoryPromptContext.ObjectiveContext(projection.worldTimeMinutes(),
                        StoryPromptContext.WorldClock.from(projection.worldTimeMinutes()), projection.coreState(),
                        List.of(), List.of(item, character), List.of(),
                        new StoryPromptContext.InventoryView(true, List.of(), List.of(item)), null),
                new StoryPromptContext.CognitionContext(List.of()), new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("", null), new StoryPromptContext.ActionContext(action));
    }

    private void print(String scenario, StoryTurnAgent.ResolvedAction resolved) {
        var result = resolved.modelResults().getFirst();
        System.out.printf("ACTION_CONTRACT scenario=%s prompt=%d completion=%d latencyMs=%d contract=%s%n",
                scenario, result.inputTokens(), result.outputTokens(), result.latencyMs(), resolved.contract());
    }

    private String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate.strip();
    }
}

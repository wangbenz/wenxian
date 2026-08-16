package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.CharacterPrecondition;
import com.wenxian.game.story.domain.StoryActionContract.ActionMode;
import com.wenxian.game.story.domain.StoryActionContract.Conflict;
import com.wenxian.game.story.domain.StoryActionContract.EntityReference;
import com.wenxian.game.story.domain.StoryActionContract.ItemTransition;
import com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution;
import com.wenxian.game.story.domain.StoryActionContract.ReferencePersistence;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.RelationshipValue;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryActionContractValidatorTest {
    private final StoryActionContractValidator validator = new StoryActionContractValidator();

    @Test
    void acceptsGiftWhenItemIsCurrentlyHeldAndTargetCharacterExists() {
        StoryProjection projection = projection(new CustodyValue(HolderType.PLAYER, null));
        StoryActionContract contract = contract(new CustodyValue(HolderType.PLAYER, null),
                new CustodyValue(HolderType.CHARACTER, "character-old-woman"));

        assertDoesNotThrow(() -> validator.validate(projection, contract));
    }

    @Test
    void rejectsTakingBackItemWhenPreviousGiftNeverBecameFact() {
        StoryProjection projection = projection(new CustodyValue(HolderType.PLAYER, null));
        StoryActionContract contract = contract(new CustodyValue(HolderType.CHARACTER, "character-old-woman"),
                new CustodyValue(HolderType.PLAYER, null));

        StoryActionPreconditionException error = assertThrows(StoryActionPreconditionException.class,
                () -> validator.validate(projection, contract));

        assertEquals("“断剑尖”当前仍由你保管，并非由人物“老妇”保管。", error.getMessage());
    }

    @Test
    void rejectsUsingRemovedItemWhenActionClaimsItIsInPlayerInventory() {
        StoryProjection projection = projection(new CustodyValue(HolderType.NONE, null));
        StoryActionContract contract = new StoryActionContract("从行囊取出断剑尖使用", true,
                List.of(new EntityReference("行囊中的断剑尖", ReferenceResolution.EXISTING,
                        "item-sword-tip", "ITEM", "断剑尖", "SOURCE",
                        new CustodyValue(HolderType.PLAYER, null))),
                List.of(new ItemTransition("item-sword-tip", new CustodyValue(HolderType.PLAYER, null),
                        new CustodyValue(HolderType.NONE, null), 1)),
                List.of(), "使用后停下", new Conflict(false, "", ""));

        StoryActionPreconditionException error = assertThrows(StoryActionPreconditionException.class,
                () -> validator.validate(projection, contract));

        assertEquals("“断剑尖”当前已经消耗或不再存在，不满足本轮行动依赖的保管状态。", error.getMessage());
    }

    @Test
    void rejectsSemanticConflictReturnedBeforeNarrativeGeneration() {
        StoryActionContract contract = new StoryActionContract("取回已赠物品", true, List.of(), List.of(),
                List.of(), "", new Conflict(true, "当前记录中赠送并未完成。", "可以重新赠送，或直接检查行囊。"));

        StoryActionPreconditionException error = assertThrows(StoryActionPreconditionException.class,
                () -> validator.validate(projection(new CustodyValue(HolderType.PLAYER, null)), contract));

        assertEquals("当前记录中赠送并未完成。", error.getMessage());
        assertEquals("可以重新赠送，或直接检查行囊。", error.correction());
    }

    @Test
    void rejectsActionThatReliesOnAnUnconfirmedRelationship() {
        StoryActionContract contract = new StoryActionContract("请师父传功", false,
                List.of(existing("老妇", "character-old-woman", "CHARACTER", "TARGET")), List.of(),
                List.of(new CharacterPrecondition("character-old-woman", LifecycleState.ACTIVE, "师徒")),
                "", new Conflict(false, "", ""));

        StoryActionPreconditionException error = assertThrows(StoryActionPreconditionException.class,
                () -> validator.validate(projection(new CustodyValue(HolderType.PLAYER, null)), contract));

        assertEquals("你与人物“老妇”当前确认的关系是“旧识”。", error.getMessage());
    }

    @Test
    void rejectsExistingReferenceWhoseCanonicalNameDoesNotMatchStableEntity() {
        StoryActionContract contract = new StoryActionContract("查看瓦片", false,
                List.of(new EntityReference("青色瓦片", ReferenceResolution.EXISTING,
                        "item-sword-tip", "ITEM", "青色瓦片", "SOURCE", null)),
                List.of(), List.of(), "查看后停下", new Conflict(false, "", ""));

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(projection(new CustodyValue(HolderType.PLAYER, null)), contract));
    }

    @Test
    void acceptsExplicitNewItemWithoutPretendingItAlreadyExists() {
        StoryActionContract contract = new StoryActionContract("拾起瓦片", true,
                List.of(new EntityReference("刚掉落的青色瓦片", ReferenceResolution.NEW,
                        null, "ITEM", "青色瓦片碎片", "TARGET",
                        new CustodyValue(HolderType.PLAYER, null))),
                List.of(), List.of(), "放入行囊后停下", new Conflict(false, "", ""));

        assertDoesNotThrow(() -> validator.validate(
                projection(new CustodyValue(HolderType.PLAYER, null)), contract));
    }

    @Test
    void acceptsLandmarkReferenceWithoutTreatingItAsInventory() {
        StoryActionContract contract = new StoryActionContract("观察水井", false,
                List.of(new EntityReference("水井", ReferenceResolution.NEW, null,
                        "LANDMARK", "水井", "MENTION", null)),
                List.of(), List.of(), "留在井边", new Conflict(false, "", ""));

        assertDoesNotThrow(() -> validator.validate(
                projection(new CustodyValue(HolderType.PLAYER, null)), contract));
    }

    @Test
    void rejectsCustodyOnNonItemReference() {
        StoryActionContract contract = new StoryActionContract("观察水井", false,
                List.of(new EntityReference("水井", ReferenceResolution.NEW, null,
                        "LANDMARK", "水井", "MENTION",
                        new CustodyValue(HolderType.LOCATION, "村中"))),
                List.of(), List.of(), "", new Conflict(false, "", ""));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(projection(new CustodyValue(HolderType.PLAYER, null)), contract));
        assertTrue(error.getMessage().contains("只有可转移物品"));
    }

    @Test
    void acceptsSceneEntityEstablishedByAProvidedSource() {
        EntityReference oldZhou = new EntityReference("老周", ReferenceResolution.ESTABLISHED,
                null, "CHARACTER", "老周", "TARGET", null, List.of("turn:12"));
        StoryActionContract contract = new StoryActionContract("尝试追上老周询问", ActionMode.ATTEMPT,
                false, List.of(oldZhou), List.of(), List.of(), null, "询问后停下",
                new Conflict(false, "", ""));

        assertDoesNotThrow(() -> validator.validate(
                projection(new CustodyValue(HolderType.PLAYER, null)), contract, Set.of("turn:12")));
    }

    @Test
    void rejectsGiftThatSilentlyBindsMentionedPersonToAnotherExistingCharacter() {
        EntityReference liu = new EntityReference("柳青儿", ReferenceResolution.UNRESOLVED,
                null, "CHARACTER", "柳青儿", "TARGET", null, List.of(), "r2");
        StoryActionContract contract = new StoryActionContract("把断剑尖交给柳青儿", ActionMode.ATTEMPT,
                true, List.of(existing("断剑尖", "item-sword-tip", "ITEM", "SOURCE"), liu),
                List.of(new ItemTransition("item-sword-tip", new CustodyValue(HolderType.PLAYER, null),
                        new CustodyValue(HolderType.CHARACTER, "character-old-woman"), 1, "")),
                List.of(), null, "交付后停下", new Conflict(false, "", ""));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(projection(new CustodyValue(HolderType.PLAYER, null)), contract));

        assertTrue(error.getMessage().contains("没有出现在玩家本轮的稳定实体引用"));
    }

    @Test
    void acceptsGiftToSourceEstablishedCharacterThroughTurnLocalReference() {
        EntityReference liu = new EntityReference("柳青儿", ReferenceResolution.ESTABLISHED,
                null, "CHARACTER", "柳青儿", "TARGET", null,
                List.of("initial:origin"), "r2");
        StoryActionContract raw = new StoryActionContract("把断剑尖交给柳青儿", ActionMode.ATTEMPT,
                true, List.of(existing("断剑尖", "item-sword-tip", "ITEM", "SOURCE"), liu),
                List.of(new ItemTransition("item-sword-tip", new CustodyValue(HolderType.PLAYER, null),
                        new CustodyValue(HolderType.CHARACTER, null), 1, "r2")),
                List.of(), null, "交付后停下", new Conflict(false, "", ""));
        StoryActionContract canonical = new StoryActionContractCanonicalizer()
                .canonicalize(projection(new CustodyValue(HolderType.PLAYER, null)), raw);

        assertDoesNotThrow(() -> validator.validate(
                projection(new CustodyValue(HolderType.PLAYER, null)), canonical, Set.of("initial:origin")));
        assertEquals("contract-ref:r2", canonical.itemTransitions().getFirst().target().holderRef());
    }

    @Test
    void canonicalizerUsesTransitionStartCustodyWhenReferenceCopiedTheFinalHolder() {
        EntityReference item = new EntityReference("柳字玉坠", ReferenceResolution.EXISTING,
                "item-sword-tip", "ITEM", "断剑尖", "TARGET",
                new CustodyValue(HolderType.CHARACTER, "character-old-woman"), List.of(), "r1");
        EntityReference target = new EntityReference("老妇", ReferenceResolution.EXISTING,
                "character-old-woman", "CHARACTER", "老妇", "TARGET", null, List.of(), "r2");
        StoryActionContract raw = new StoryActionContract("把断剑尖交给老妇", ActionMode.ATTEMPT,
                true, List.of(item, target),
                List.of(new ItemTransition("item-sword-tip", new CustodyValue(HolderType.PLAYER, null),
                        new CustodyValue(HolderType.CHARACTER, "character-old-woman"), 1, "r2")),
                List.of(), null, "交付后停下", new Conflict(false, "", ""));

        StoryActionContract canonical = new StoryActionContractCanonicalizer()
                .canonicalize(projection(new CustodyValue(HolderType.PLAYER, null)), raw);

        assertEquals(new CustodyValue(HolderType.PLAYER, null),
                canonical.references().getFirst().expectedCustody());
        assertDoesNotThrow(() -> validator.validate(
                projection(new CustodyValue(HolderType.PLAYER, null)), canonical));
    }

    @Test
    void acceptsLegacyPlayerCustodyThatStillContainsProtagonistName() {
        StoryProjection projection = projection(new CustodyValue(HolderType.PLAYER, "温砚舟"));
        StoryActionContract contract = contract(new CustodyValue(HolderType.PLAYER, null),
                new CustodyValue(HolderType.CHARACTER, "character-old-woman"));

        assertDoesNotThrow(() -> validator.validate(projection, contract));
    }

    @Test
    void rejectsEstablishedReferenceWhoseEvidenceWasNotActuallyProvided() {
        EntityReference oldZhou = new EntityReference("老周", ReferenceResolution.ESTABLISHED,
                null, "CHARACTER", "老周", "TARGET", null, List.of("turn:999"));
        StoryActionContract contract = new StoryActionContract("询问老周", ActionMode.ATTEMPT,
                false, List.of(oldZhou), List.of(), List.of(), null, "",
                new Conflict(false, "", ""));

        assertThrows(StoryActionPreconditionException.class, () -> validator.validate(
                projection(new CustodyValue(HolderType.PLAYER, null)), contract, Set.of("turn:12")));
    }

    @Test
    void allowsUnknownTargetWhenPlayerIsOnlyTryingToFindIt() {
        EntityReference sword = new EntityReference("传说中的仙剑", ReferenceResolution.UNRESOLVED,
                null, "ITEM", "传说中的仙剑", "TARGET", null, List.of());
        StoryActionContract contract = new StoryActionContract("尝试寻找仙剑", ActionMode.ATTEMPT,
                false, List.of(sword), List.of(), List.of(), null, "找到线索后停下",
                new Conflict(false, "", ""));

        assertDoesNotThrow(() -> validator.validate(
                projection(new CustodyValue(HolderType.PLAYER, null)), contract, Set.of()));
    }

    @Test
    void ignoresUnresolvedPlayerCustodyWhenNoItemMutationWasDetected() {
        EntityReference injuryMisclassifiedAsItem = new EntityReference("伤势", ReferenceResolution.UNRESOLVED,
                null, "ITEM", "伤势", "MENTION",
                new CustodyValue(HolderType.PLAYER, null), List.of());
        StoryActionContract contract = new StoryActionContract("检查伤势", ActionMode.QUERY,
                false, List.of(injuryMisclassifiedAsItem), List.of(), List.of(), null,
                "检查后停下", new Conflict(false, "", ""));

        assertDoesNotThrow(() -> validator.validate(
                projection(new CustodyValue(HolderType.PLAYER, null)), contract, Set.of()));
    }

    @Test
    void rejectsUnknownTargetWhenPlayerClaimsItAlreadyBelongsToThem() {
        EntityReference sword = new EntityReference("我行囊里的仙剑", ReferenceResolution.UNRESOLVED,
                null, "ITEM", "仙剑", "SOURCE", new CustodyValue(HolderType.PLAYER, null), List.of());
        StoryActionContract contract = new StoryActionContract("使用我已有的仙剑", ActionMode.ASSERT_FACT,
                true, List.of(sword), List.of(), List.of(), null, "",
                new Conflict(false, "", ""));

        assertThrows(StoryActionPreconditionException.class, () -> validator.validate(
                projection(new CustodyValue(HolderType.PLAYER, null)), contract, Set.of()));
    }

    @Test
    void rejectsUnresolvedPlayerOwnedItemEvenIfModelMisclassifiesActionAsAttempt() {
        EntityReference tile = new EntityReference("我行囊里的青色瓦片碎片", ReferenceResolution.UNRESOLVED,
                null, "ITEM", "青色瓦片碎片", "SOURCE",
                new CustodyValue(HolderType.PLAYER, null), List.of());
        StoryActionContract contract = new StoryActionContract("从行囊取出瓦片观察", ActionMode.ATTEMPT,
                true, List.of(tile), List.of(), List.of(), null, "观察后停下",
                new Conflict(false, "", ""));

        StoryActionPreconditionException error = assertThrows(StoryActionPreconditionException.class,
                () -> validator.validate(projection(new CustodyValue(HolderType.PLAYER, null)), contract));

        assertTrue(error.getMessage().contains("没有证据支持你已经持有"));
    }

    @Test
    void acceptsTransientSceneCharacterWithoutStableIdDuringAnAttempt() {
        EntityReference stallOwner = new EntityReference("摊主", ReferenceResolution.NEW,
                null, "CHARACTER", "摊主", "SCENE", null, List.of(), "scene-owner",
                ReferencePersistence.TRANSIENT, false);
        StoryActionContract contract = new StoryActionContract("询问摊主药材价格", ActionMode.ATTEMPT,
                false, List.of(stallOwner), List.of(), List.of(), null,
                "问完价格后停下", new Conflict(false, "", ""));

        assertDoesNotThrow(() -> validator.validate(
                projection(new CustodyValue(HolderType.PLAYER, null)), contract));
    }

    @Test
    void rejectsUsingTransientReferenceToBypassAnExistingFactAssertion() {
        EntityReference claimedAbility = new EntityReference("我早已掌握的御剑术", ReferenceResolution.NEW,
                null, "ABILITY", "御剑术", "SOURCE", null, List.of(), "claimed-ability",
                ReferencePersistence.TRANSIENT, false);
        StoryActionContract contract = new StoryActionContract("施展我早已掌握的御剑术", ActionMode.ASSERT_FACT,
                false, List.of(claimedAbility), List.of(), List.of(), null,
                "施展后停下", new Conflict(false, "", ""));

        StoryActionPreconditionException error = assertThrows(StoryActionPreconditionException.class,
                () -> validator.validate(projection(new CustodyValue(HolderType.PLAYER, null)), contract));

        assertTrue(error.getMessage().contains("不能只作为本轮临时叙事对象"));
    }

    @Test
    void rejectsTransientReferenceAsADurableResultTarget() {
        EntityReference learnedAbility = new EntityReference("学习御剑术", ReferenceResolution.NEW,
                null, "ABILITY", "御剑术", "RESULT", null, List.of(), "learned-ability",
                ReferencePersistence.TRANSIENT, true);
        StoryActionContract contract = new StoryActionContract("尝试学习御剑术", ActionMode.ATTEMPT,
                false, List.of(learnedAbility), List.of(), List.of(), null,
                "学习尝试结束后停下", new Conflict(false, "", ""));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(projection(new CustodyValue(HolderType.PLAYER, null)), contract));

        assertTrue(error.getMessage().contains("结果目标不能同时声明"));
    }

    @Test
    void rejectsDurableNewItemWithoutTargetCustodyWhenItemStateWillChange() {
        EntityReference parcel = new EntityReference("领取药包", ReferenceResolution.NEW,
                null, "ITEM", "送药药包", "TARGET", null, List.of(), "delivery-parcel",
                ReferencePersistence.PROMOTE_ON_CHANGE, true);
        StoryActionContract contract = new StoryActionContract("领取送药药包", ActionMode.ATTEMPT,
                true, List.of(parcel), List.of(), List.of(), null,
                "领到药包后停下", new Conflict(false, "", ""));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(projection(new CustodyValue(HolderType.PLAYER, null)), contract));

        assertTrue(error.getMessage().contains("缺少目标保管者"));
    }

    private StoryActionContract contract(CustodyValue from, CustodyValue target) {
        return new StoryActionContract("处置断剑尖", true,
                List.of(existing("断剑尖", "item-sword-tip", "ITEM", "SOURCE"),
                        existing("老妇", "character-old-woman", "CHARACTER", "TARGET")),
                List.of(new ItemTransition("item-sword-tip", from, target, 1)),
                List.of(new CharacterPrecondition("character-old-woman", LifecycleState.ACTIVE)),
                "完成本次交付后停下", new Conflict(false, "", ""));
    }

    private EntityReference existing(String mention, String id, String type, String role) {
        return new EntityReference(mention, ReferenceResolution.EXISTING, id, type, mention, role, null);
    }

    private StoryProjection projection(CustodyValue custody) {
        EntityState item = new EntityState("item-sword-tip", "ITEM", "断剑尖", "完好", "一截断剑",
                null, true, new QuantityValue(true, 1), custody.holderType() == HolderType.PLAYER,
                custody, null, null);
        EntityState character = new EntityState("character-old-woman", "CHARACTER", "老妇", "在村中",
                "曾与主角交谈", null, true, null, null, null, null, LifecycleState.ACTIVE,
                new RelationshipValue("旧识", "曾经交谈"));
        return new StoryProjection(0, new CoreState("废屋", "平静", "凡俗", "问仙"),
                Map.of(item.id(), item, character.id(), character), Map.of(), Map.of(), List.of());
    }
}

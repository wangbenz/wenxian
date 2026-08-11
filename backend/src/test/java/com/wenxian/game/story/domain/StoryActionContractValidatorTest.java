package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.CharacterPrecondition;
import com.wenxian.game.story.domain.StoryActionContract.Conflict;
import com.wenxian.game.story.domain.StoryActionContract.EntityReference;
import com.wenxian.game.story.domain.StoryActionContract.ItemTransition;
import com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

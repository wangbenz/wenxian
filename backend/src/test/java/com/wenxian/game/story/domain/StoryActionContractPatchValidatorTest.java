package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.Conflict;
import com.wenxian.game.story.domain.StoryActionContract.ItemTransition;
import com.wenxian.game.story.domain.StoryActionContract.EntityReference;
import com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.TimePassage;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoryActionContractPatchValidatorTest {
    private final StoryActionContractPatchValidator validator = new StoryActionContractPatchValidator();

    @Test
    void requiresCandidatePatchToPersistContractTargetHolder() {
        StoryActionContract contract = contract();
        StoryPatch missingCustody = patch(new EntityPatch(EntityOperation.UPDATE, "item-sword-tip", "ITEM",
                null, "已经交付", null, null, new QuantityValue(true, 1), false,
                null, null));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(contract, missingCustody));
    }

    @Test
    void acceptsCandidateThatPersistsExactCharacterCustody() {
        StoryActionContract contract = contract();
        StoryPatch candidate = patch(new EntityPatch(EntityOperation.UPDATE, "item-sword-tip", "ITEM",
                null, "由老妇保管", null, null, new QuantityValue(true, 1), false,
                new CustodyValue(HolderType.CHARACTER, "character-old-woman"), null, null));

        assertDoesNotThrow(() -> validator.validate(contract, candidate));
    }

    @Test
    void requiresNewCarriedItemToBeCreatedWithContractNameAndCustody() {
        StoryActionContract contract = new StoryActionContract("拾起瓦片", true,
                List.of(new EntityReference("青色瓦片", ReferenceResolution.NEW, null, "ITEM",
                        "青色瓦片碎片", "TARGET", new CustodyValue(HolderType.PLAYER, null))),
                List.of(), List.of(), "放入行囊后停下", new Conflict(false, "", ""));
        StoryPatch candidate = patch(new EntityPatch(EntityOperation.CREATE, null, "ITEM",
                "青色瓦片碎片", "刚拾起", null, null, new QuantityValue(true, 1), true,
                new CustodyValue(HolderType.PLAYER, null), null, null));

        assertDoesNotThrow(() -> validator.validate(contract, candidate));
    }

    @Test
    void rejectsNewItemPatchThatCreatesAReplacementWithDifferentName() {
        StoryActionContract contract = new StoryActionContract("拾起瓦片", true,
                List.of(new EntityReference("青色瓦片", ReferenceResolution.NEW, null, "ITEM",
                        "青色瓦片碎片", "TARGET", new CustodyValue(HolderType.PLAYER, null))),
                List.of(), List.of(), "放入行囊后停下", new Conflict(false, "", ""));
        StoryPatch candidate = patch(new EntityPatch(EntityOperation.CREATE, null, "ITEM",
                "残剑诀玉简", "刚拾起", null, null, new QuantityValue(true, 1), true,
                new CustodyValue(HolderType.PLAYER, null), null, null));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(contract, candidate));
    }

    @Test
    void failedAttemptDoesNotRequireTheNewItemToExist() {
        StoryActionContract contract = new StoryActionContract("尝试拾起瓦片", true,
                List.of(new EntityReference("青色瓦片", ReferenceResolution.NEW, null, "ITEM",
                        "青色瓦片碎片", "TARGET", new CustodyValue(HolderType.PLAYER, null))),
                List.of(), List.of(), "尝试结束后停下", new Conflict(false, "", ""));
        StoryPatch failed = new StoryPatch("未能拾取", "瓦片坠入缝隙。",
                new TimePassage(5, TimeResolution.COMPLETE, null), null, List.of(), List.of(),
                List.of(), List.of(), new StoryPatch.DecisionCheckpoint(false, null), List.of(),
                "未取得瓦片。", new StoryPatch.TurnOutcome(StoryPatch.OutcomeResolution.FAILED,
                "瓦片坠入缝隙，未能取得"));

        assertDoesNotThrow(() -> validator.validate(contract, failed));
    }

    @Test
    void failedAttemptCannotStillCreateTheNewItem() {
        StoryActionContract contract = new StoryActionContract("尝试拾起瓦片", true,
                List.of(new EntityReference("青色瓦片", ReferenceResolution.NEW, null, "ITEM",
                        "青色瓦片碎片", "TARGET", new CustodyValue(HolderType.PLAYER, null))),
                List.of(), List.of(), "尝试结束后停下", new Conflict(false, "", ""));
        EntityPatch created = new EntityPatch(EntityOperation.CREATE, null, "ITEM",
                "青色瓦片碎片", "刚拾起", null, null, new QuantityValue(true, 1), true,
                new CustodyValue(HolderType.PLAYER, null), null, null);
        StoryPatch failed = new StoryPatch("未能拾取", "瓦片坠入缝隙。",
                new TimePassage(5, TimeResolution.COMPLETE, null), null, List.of(created), List.of(),
                List.of(), List.of(), new StoryPatch.DecisionCheckpoint(false, null), List.of(),
                "未取得瓦片。", new StoryPatch.TurnOutcome(StoryPatch.OutcomeResolution.FAILED,
                "瓦片坠入缝隙，未能取得"));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(contract, failed));
    }

    @Test
    void establishedItemMutationMustPromoteTheSourceItemIntoStableState() {
        StoryActionContract contract = new StoryActionContract("服用根源中的醒尘丹", true,
                List.of(new EntityReference("醒尘丹", ReferenceResolution.ESTABLISHED, null, "ITEM",
                        "醒尘丹", "SOURCE", new CustodyValue(HolderType.PLAYER, null),
                        List.of("initial:origin"))),
                List.of(), List.of(), "服用后停下", new Conflict(false, "", ""));
        StoryPatch candidate = patch(new EntityPatch(EntityOperation.CREATE, null, "ITEM",
                "醒尘丹", "已经服用", "根源中携带的丹药已经消耗", null,
                new QuantityValue(true, 0), false, new CustodyValue(HolderType.NONE, null), null, null));

        assertDoesNotThrow(() -> validator.validate(contract, candidate));
    }

    @Test
    void establishedItemMutationCannotDisappearOnlyInNarrative() {
        StoryActionContract contract = new StoryActionContract("服用根源中的醒尘丹", true,
                List.of(new EntityReference("醒尘丹", ReferenceResolution.ESTABLISHED, null, "ITEM",
                        "醒尘丹", "SOURCE", new CustodyValue(HolderType.PLAYER, null),
                        List.of("initial:origin"))),
                List.of(), List.of(), "服用后停下", new Conflict(false, "", ""));
        StoryPatch unrelated = patch(new EntityPatch(EntityOperation.CREATE, null, "ITEM",
                "空瓷瓶", "留在手中", "服药后留下的瓷瓶", null,
                new QuantityValue(true, 1), true, new CustodyValue(HolderType.PLAYER, null), null, null));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(contract, unrelated));
    }

    @Test
    void acceptsPartialConsumptionWhenQuantityDropsAndRemainingStackStaysWithPlayer() {
        StoryActionContract contract = partialContract();
        StoryPatch candidate = patch(new EntityPatch(EntityOperation.UPDATE, "item-pill", "ITEM",
                null, "余下两颗", null, null, new QuantityValue(true, 2), true,
                new CustodyValue(HolderType.PLAYER, null), null, null));

        assertDoesNotThrow(() -> validator.validate(projectionWithPills(), contract, candidate));
    }

    @Test
    void rejectsPartialConsumptionWhenCandidateDoesNotDeductQuantity() {
        StoryActionContract contract = partialContract();
        StoryPatch candidate = patch(new EntityPatch(EntityOperation.UPDATE, "item-pill", "ITEM",
                null, "仍有三颗", null, null, new QuantityValue(true, 3), true,
                new CustodyValue(HolderType.PLAYER, null), null, null));

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(projectionWithPills(), contract, candidate));
    }

    @Test
    void rejectsExistingItemCustodyChangeMissingFromActionContract() {
        CustodyValue player = new CustodyValue(HolderType.PLAYER, null);
        EntityState jade = new EntityState("item-jade", "ITEM", "《引泉诀》玉简", "完好", "随身保管",
                null, true, new QuantityValue(true, 1), true, player, null, null);
        StoryProjection before = new StoryProjection(0,
                new CoreState("卖画老人摊前", "平静", "启灵", "问道"),
                Map.of(jade.id(), jade), Map.of(), Map.of(), List.of());
        StoryActionContract inspectOnly = new StoryActionContract("拿出玉简请老人参详", false,
                List.of(), List.of(), List.of(), "等待老人答复", new Conflict(false, "", ""));
        StoryPatch candidate = patch(new EntityPatch(EntityOperation.UPDATE, jade.id(), "ITEM",
                null, "老人手中", null, null, new QuantityValue(true, 1), false,
                new CustodyValue(HolderType.CHARACTER, "character-wrong"), null, null));

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(before, inspectOnly, candidate));
    }

    @Test
    void allowsExistingItemSummaryUpdateWhenCustodyAndQuantityStayUnchanged() {
        CustodyValue player = new CustodyValue(HolderType.PLAYER, null);
        EntityState jade = new EntityState("item-jade", "ITEM", "《引泉诀》玉简", "完好", "随身保管",
                null, true, new QuantityValue(true, 1), true, player, null, null);
        StoryProjection before = new StoryProjection(0,
                new CoreState("卖画老人摊前", "平静", "启灵", "问道"),
                Map.of(jade.id(), jade), Map.of(), Map.of(), List.of());
        StoryActionContract inspectOnly = new StoryActionContract("查看玉简", false,
                List.of(), List.of(), List.of(), "查看后停下", new Conflict(false, "", ""));
        StoryPatch candidate = patch(new EntityPatch(EntityOperation.UPDATE, jade.id(), "ITEM",
                null, "仍在身边", "确认玉简纹路清晰", null, new QuantityValue(true, 1), true,
                player, null, null));

        assertDoesNotThrow(() -> validator.validate(before, inspectOnly, candidate));
    }

    @Test
    void sourceCharacterReceivingItemMustBeCreatedWithMatchingTurnReference() {
        EntityReference liu = new EntityReference("柳青儿", ReferenceResolution.ESTABLISHED,
                null, "CHARACTER", "柳青儿", "TARGET", null,
                List.of("initial:origin"), "r2");
        StoryActionContract contract = new StoryActionContract("赠予柳青儿", true, List.of(liu),
                List.of(new ItemTransition("item-sword-tip", new CustodyValue(HolderType.PLAYER, null),
                        new CustodyValue(HolderType.CHARACTER, "contract-ref:r2"), 1, "r2")),
                List.of(), "交付后停下", new Conflict(false, "", ""));
        EntityPatch item = new EntityPatch(EntityOperation.UPDATE, "item-sword-tip", "ITEM",
                null, "已经交付", null, null, new QuantityValue(true, 1), false,
                new CustodyValue(HolderType.CHARACTER, "contract-ref:r2"), null, null);
        EntityPatch character = new EntityPatch(EntityOperation.CREATE, null, "CHARACTER",
                "柳青儿", "在驿站", "主角在初始经历中确认的妹妹", null, null, null,
                null, null, new StoryPatch.LifecycleValue(StoryPatch.LifecycleState.ACTIVE, "初始来源确认"),
                null, "r2");

        assertDoesNotThrow(() -> validator.validate(contract,
                new StoryPatch("交付", "你把玉坠交给柳青儿。",
                        new TimePassage(5, TimeResolution.COMPLETE, null), null,
                        List.of(item, character), List.of(), List.of(), List.of(),
                        new StoryPatch.DecisionCheckpoint(false, null), List.of(), "已经交付。")));
    }

    @Test
    void rejectsSourceCharacterPromotionWithWrongReferenceKey() {
        EntityReference liu = new EntityReference("柳青儿", ReferenceResolution.ESTABLISHED,
                null, "CHARACTER", "柳青儿", "TARGET", null,
                List.of("initial:origin"), "r2");
        StoryActionContract contract = new StoryActionContract("赠予柳青儿", true, List.of(liu),
                List.of(new ItemTransition("item-sword-tip", new CustodyValue(HolderType.PLAYER, null),
                        new CustodyValue(HolderType.CHARACTER, "contract-ref:r2"), 1, "r2")),
                List.of(), "交付后停下", new Conflict(false, "", ""));
        EntityPatch item = new EntityPatch(EntityOperation.UPDATE, "item-sword-tip", "ITEM",
                null, "已经交付", null, null, new QuantityValue(true, 1), false,
                new CustodyValue(HolderType.CHARACTER, "contract-ref:r2"), null, null);
        EntityPatch wrong = new EntityPatch(EntityOperation.CREATE, null, "CHARACTER",
                "老周", "在驿站", "驿站伙计", null, null, null, null, null,
                new StoryPatch.LifecycleValue(StoryPatch.LifecycleState.ACTIVE, "本轮出现"), null, "r9");
        StoryPatch candidate = new StoryPatch("交付", "你把玉坠交给了另一个人。",
                new TimePassage(5, TimeResolution.COMPLETE, null), null, List.of(item, wrong), List.of(),
                List.of(), List.of(), new StoryPatch.DecisionCheckpoint(false, null), List.of(), "未正确交付。 ");

        assertThrows(IllegalArgumentException.class, () -> validator.validate(contract, candidate));
    }

    private StoryActionContract contract() {
        return new StoryActionContract("赠送断剑尖", true, List.of(),
                List.of(new ItemTransition("item-sword-tip", new CustodyValue(HolderType.PLAYER, null),
                        new CustodyValue(HolderType.CHARACTER, "character-old-woman"), 1)),
                List.of(), "交付后停下", new Conflict(false, "", ""));
    }

    private StoryActionContract partialContract() {
        CustodyValue player = new CustodyValue(HolderType.PLAYER, null);
        return new StoryActionContract("服用一颗丹药", true, List.of(),
                List.of(new ItemTransition("item-pill", player, player, 1)),
                List.of(), "服用后停下", new Conflict(false, "", ""));
    }

    private StoryProjection projectionWithPills() {
        CustodyValue player = new CustodyValue(HolderType.PLAYER, null);
        EntityState item = new EntityState("item-pill", "ITEM", "莹白丹药", "完好", "瓷瓶内三颗",
                null, true, new QuantityValue(true, 3), true, player, null, null);
        return new StoryProjection(0, new CoreState("石室", "平静", "凡俗", "问仙"),
                Map.of(item.id(), item), Map.of(), Map.of(), List.of());
    }

    private StoryPatch patch(EntityPatch entity) {
        return new StoryPatch("交付", "你把断剑尖交给老妇，然后停下。",
                new TimePassage(5, TimeResolution.COMPLETE, null), null, List.of(entity), List.of(),
                List.of(), List.of(), new StoryPatch.DecisionCheckpoint(false, null), List.of(), "已完成交付。");
    }
}

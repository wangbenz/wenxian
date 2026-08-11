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
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private StoryActionContract contract() {
        return new StoryActionContract("赠送断剑尖", true, List.of(),
                List.of(new ItemTransition("item-sword-tip", new CustodyValue(HolderType.PLAYER, null),
                        new CustodyValue(HolderType.CHARACTER, "character-old-woman"), 1)),
                List.of(), "交付后停下", new Conflict(false, "", ""));
    }

    private StoryPatch patch(EntityPatch entity) {
        return new StoryPatch("交付", "你把断剑尖交给老妇，然后停下。",
                new TimePassage(5, TimeResolution.COMPLETE, null), null, List.of(entity), List.of(),
                List.of(), List.of(), new StoryPatch.DecisionCheckpoint(false, null), List.of(), "已完成交付。");
    }
}

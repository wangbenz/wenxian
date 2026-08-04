package com.wenxian.game.v014.service;

import com.wenxian.game.v014.model.AiTurnEnvelope;
import com.wenxian.game.v014.model.V014State;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V014StateReducerTest {
    private final V014StateReducer reducer = new V014StateReducer();

    @Test
    void explicitThreeYearRetreatOverridesModelGuessAndKeepsLightStateConsistent() {
        V014State before = state();
        AiTurnEnvelope envelope = new AiTurnEnvelope("三年出关", "山中三载，旧雪化尽。", 60L,
                "青岐山洞府", "气息沉凝", "筑基初期", "求得真正自在", List.of(new AiTurnEnvelope.ChoiceDraft("下山", "看看山外变化")),
                List.of(), List.of(), List.of(),
                List.of(new AiTurnEnvelope.MemoryDraft("三年闭关", "在洞府中闭关三年。", "KEY")), "闭关三年后出关");

        V014StateReducer.Applied result = reducer.apply(before, "在洞府闭关三年", envelope);

        assertEquals(3L * 360 * 1440, result.elapsedMinutes());
        assertEquals(1, result.state().turnNumber());
        assertEquals("青岐山洞府", result.state().currentLocation());
        assertEquals("筑基初期", result.state().protagonist().realm());
        assertEquals("求得真正自在", result.state().protagonist().lifeGoal());
        assertEquals("三年闭关", result.state().memories().getLast().title());
    }

    @Test
    void itemLifecycleAndImportantCharactersAreUpdatedWithoutPersistingPassersBy() {
        V014State before = state();
        AiTurnEnvelope envelope = new AiTurnEnvelope("旧市易物", "你把青玉卖给了商会。", 30L, null, null, null, null,
                List.of(), List.of(new AiTurnEnvelope.EntityPatch("REMOVE", "青玉", null, null, "SOLD", null, 0)),
                List.of(new AiTurnEnvelope.EntityPatch("ADD", "听风诀", "功法", "闻风识意。", "MASTERED", "商会秘卷", null)),
                List.of(new AiTurnEnvelope.CharacterPatch("ADD", "卖茶老人", "TRANSIENT", "路人", "一面之缘", "ACTIVE", "旧市"),
                        new AiTurnEnvelope.CharacterPatch("ADD", "沈知微", "IMPORTANT", "商会执事", "互有试探", "ACTIVE", "旧市")),
                List.of(), "完成旧市交易");

        V014State after = reducer.apply(before, "卖掉青玉并与执事交谈", envelope).state();

        assertEquals("SOLD", after.inventory().getFirst().status());
        assertFalse(after.inventory().getFirst().inPossession());
        assertEquals("听风诀", after.abilities().getFirst().name());
        assertEquals(1, after.characters().size());
        assertEquals("沈知微", after.characters().getFirst().name());
        assertTrue(after.choices().size() >= 3, "模型漏掉选项时仍提供温和降级选项");
    }

    @Test
    void freeFormItemNotesStayPossessedAndPunctuationAliasesUpdateOneItem() {
        V014State before = state();
        AiTurnEnvelope envelope = new AiTurnEnvelope("玉中留痕", "青玉显出旧纹。", 10L, null, null, null, null,
                List.of(), List.of(new AiTurnEnvelope.EntityPatch("UPDATE", "《青玉》", "信物",
                "玉中浮出家族旧纹。", "贴身藏在衣襟内", "家传", 1)), List.of(), List.of(), List.of(), "青玉显纹");

        V014State after = reducer.apply(before, "查看青玉", envelope).state();

        assertEquals(1, after.inventory().size());
        assertEquals("贴身藏在衣襟内", after.inventory().getFirst().status());
        assertTrue(after.inventory().getFirst().inPossession());
    }

    @Test
    void removingImportantCharacterOnlyStopsCurrentTracking() {
        V014State before = stateWithCharacter();
        AiTurnEnvelope envelope = new AiTurnEnvelope("别后", "沈知微离开青岐州。", 10L, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(new AiTurnEnvelope.CharacterPatch(
                "REMOVE", "沈知微", "IMPORTANT", null, null, "已远赴北地", "北地")), List.of(), "故人远行");

        V014State after = reducer.apply(before, "目送沈知微离开", envelope).state();

        assertEquals(1, after.characters().size(), "人物历史仍保存在命卷中");
        assertFalse(after.characters().getFirst().tracked());
    }

    private V014State state() {
        return new V014State(14, "v014-save-test", 0, "ACTIVE",
                new V014State.WorldProfile("XUANXU", "玄墟界", "OFFICIAL", "仙路未明", "古典修仙", "青岐州"),
                new V014State.Protagonist("陆沉", "人族", "男", "宗门弟子", "引气", "问道", "自由探索"),
                0, "青岐州", "神完气足", "初入山门", "山门在前。", List.of(),
                List.of(new V014State.Item("item-jade", "青玉", "信物", "温润旧玉", "仍在身边", "家传", 1, true)),
                List.of(), List.of(), List.of());
    }

    private V014State stateWithCharacter() {
        V014State base = state();
        return new V014State(base.schemaVersion(), base.saveId(), base.turnNumber(), base.status(), base.world(),
                base.protagonist(), base.worldTimeMinutes(), base.currentLocation(), base.condition(), base.sceneTitle(),
                base.narrative(), base.choices(), base.inventory(), base.abilities(), List.of(new V014State.Character(
                "character-shen", "沈知微", "IMPORTANT", "商会执事", "互有信任", "正在青岐州", "青岐州", true)), base.memories());
    }
}

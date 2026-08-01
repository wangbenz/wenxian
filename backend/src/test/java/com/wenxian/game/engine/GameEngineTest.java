package com.wenxian.game.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameEngineTest {
    @Test
    void refreshesDailyAbilityAtTheEndOfATurnThatCrossesIntoANewDay() {
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "mine";
        state.sceneTitle = "废矿场";
        state.currentLocation = "青州废矿场";
        state.worldYear = 3001;
        state.day = 31;
        state.minute = 560;
        state.player = new GameState.PlayerState("石见微", "SPIRIT", "FEMALE", 16, 240,
                "灵族", "TRUTH", "问道", "旧债", "凡俗", "青玉灵体", 60, 5, 6, 0);
        state.abilities.add(new GameState.AbilityRecord(
                "jade-heart", "jade-heart", "灵玉之心", "TALENT", "初醒", 1,
                "命格觉醒", "感应玉石", "每日可用一次", 1, 0,
                "DAILY", 3001 * 360 + 30, "使用后暂时疲惫"));

        AgentResult result = new AgentResult("mine-rest", "废矿场", "青州废矿场",
                3 * 1440, "CONTINUE", List.of("三日过去。", "你结束休养。"), List.of("休养结束"), List.of(),
                Map.of("condition", "STABLE"), new AgentResult.EventDraft("三日休养", "你休养了三日。", "world"),
                null, null, null, null,
                new AgentResult.ActionResolution("ACCEPTED", "", "", List.of()),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"),
                ConflictVerdict.narrativeOnly());
        GameState next = new GameEngine(new ObjectMapper().findAndRegisterModules()).apply(state, result);

        assertEquals(34, next.day);
        assertEquals(1, next.abilities.getFirst().remainingUses);
        assertEquals(3001 * 360 + 33, next.abilities.getFirst().lastResetDay);
    }
    @Test
    void advancesMultiYearActionsInsteadOfClampingThemToTwoDays() {
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "seclusion";
        state.sceneTitle = "石室";
        state.currentLocation = "青梧门洞府";
        state.worldYear = 3001;
        state.day = 12;
        state.minute = 8 * 60;
        state.player = new GameState.PlayerState("陆沉", "HUMAN", "MALE", 16, 75,
                "外门弟子", "LONGEVITY", "求长生", "旧债", "引气", "凡胎", 60, 0, 6, 0);

        AgentResult result = new AgentResult("seclusion-end", "石室", "青梧门洞府",
                3 * 360 * 1440, "CONTINUE",
                List.of("你在石室中度过三载。", "第三年春，你终于推门而出。"),
                List.of("闭关三年"), List.of(),
                Map.of("condition", "STABLE"),
                new AgentResult.EventDraft("三载闭关", "你在洞府中闭关三年。", "world"),
                null, null, null, null,
                new AgentResult.ActionResolution("ACCEPTED", "", "", List.of()),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"),
                ConflictVerdict.narrativeOnly());

        GameState next = new GameEngine(new ObjectMapper().findAndRegisterModules()).apply(state, result);

        assertEquals(3004, next.worldYear);
        assertEquals(12, next.day);
        assertEquals(8 * 60, next.minute);
        assertEquals(19, next.player.age);
        assertEquals(1, next.turn);
    }

    @Test
    void sealsTheLifeOnlyWhenTheAuthorityVerdictAllowsDeath() {
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "mortal-fight";
        state.sceneTitle = "义庄";
        state.currentLocation = "城郊义庄";
        state.worldYear = 3001;
        state.day = 1;
        state.player = new GameState.PlayerState("陆沉", "HUMAN", "MALE", 16, 75,
                "守墓人", "LONGEVITY", "活下去", "旧债", "凡俗", "孱弱", 12, 3, 6, 40);

        AgentResult result = new AgentResult(
                "death", "义庄", "城郊义庄", 1, "CONTINUE",
                List.of("剑光掠过。", "你倒在尘土中。"), List.of("伤势致命"),
                List.of(new GameState.StoryChoice("impossible", "继续", "不应保留", "default")),
                Map.of("condition", "DYING"), new AgentResult.EventDraft("剑下亡魂", "你被一剑封喉。", "risk"),
                null, null, null, null,
                new AgentResult.ActionResolution("ACCEPTED", "", "", List.of()),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"),
                new ConflictVerdict("DEAD", "OVERWHELMING_DISADVANTAGE", "DYING", true,
                        List.of("主角已濒死"), List.of("必须死亡"))
        );

        GameState dead = new GameEngine(new ObjectMapper().findAndRegisterModules()).apply(state, result);

        assertEquals("dead", dead.phase);
        assertEquals(0, dead.player.vitality);
        assertTrue(dead.choices.isEmpty());
        assertNotNull(dead.death);
        assertEquals("剑下亡魂", dead.death.title);
        assertEquals(1, dead.death.turn);
    }

    @Test
    void legacyZeroVitalityAloneDoesNotKillAnActiveLife() {
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "road";
        state.sceneTitle = "山路";
        state.currentLocation = "山路";
        state.player = new GameState.PlayerState("陆沉", "HUMAN", "MALE", 16, 75,
                "凡人", "LONGEVITY", "活下去", "旧债", "凡俗", "孱弱", 0, 0, 6, 0);

        AgentResult result = new AgentResult("road-next", "山路", "山路", 10, "CONTINUE",
                List.of("你在路旁喘息。", "片刻后继续赶路。"), List.of("气息渐稳"), List.of(),
                Map.of("condition", "STABLE"), new AgentResult.EventDraft("稍作歇息", "你没有倒下。", "world"),
                null, null, null, null,
                new AgentResult.ActionResolution("ACCEPTED", "", "", List.of()),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"),
                ConflictVerdict.narrativeOnly());

        GameState next = new GameEngine(new ObjectMapper().findAndRegisterModules()).apply(state, result);

        assertEquals("playing", next.phase);
        assertEquals("STABLE", next.player.condition);
        assertEquals(60, next.player.vitality);
    }

    @Test
    void persistsNewCharactersAndKeepsKnownRealmOnPartialUpdates() {
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "inn";
        state.sceneTitle = "客栈";
        state.currentLocation = "客栈";
        state.player = new GameState.PlayerState("陆沉", "HUMAN", "MALE", 16, 75,
                "凡人", "LONGEVITY", "活下去", "旧债", "凡俗", "凡胎", 60, 0, 6, 0);
        state.characters.add(new GameState.CharacterRecord("keeper", "掌柜", "客栈掌柜", "筑基",
                "STABLE", "剑术未明", "存活", "客栈", "冷淡", 0));

        AgentResult result = new AgentResult("inn-next", "客栈", "客栈", 10, "CONTINUE",
                List.of("一个陌生女子推门而入。", "掌柜朝她点了点头。"), List.of("新人物出现"), List.of(),
                Map.of("condition", "STABLE"), new AgentResult.EventDraft("雨夜来客", "新人物进入客栈。", "world"),
                null, null, null, null,
                new AgentResult.ActionResolution("ACCEPTED", "", "", List.of()),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"),
                ConflictVerdict.narrativeOnly(),
                List.of(
                        new AgentResult.CharacterChange("UPDATE", "keeper", "掌柜", "客栈掌柜", "筑基",
                                "STABLE", "剑术未明", "存活", "客栈", "稍有缓和"),
                        new AgentResult.CharacterChange("ADD", "visitor", "沈青禾", "陌生旅人", "引气",
                                "WOUNDED", "御剑", "存活", "客栈", "初见")
                ));

        GameState next = new GameEngine(new ObjectMapper().findAndRegisterModules()).apply(state, result);

        assertEquals(2, next.characters.size());
        assertEquals("筑基", next.characters.stream().filter(value -> "keeper".equals(value.id)).findFirst().orElseThrow().realm);
        assertEquals("引气", next.characters.stream().filter(value -> "visitor".equals(value.id)).findFirst().orElseThrow().realm);
    }

    @Test
    void doesNotInferRealmFromAnAbilityDescription() {
        GameState state = new GameState();
        state.player = new GameState.PlayerState("宁临渊", "HUMAN", "MALE", 16, 75,
                "乞儿", "LONGEVITY", "求长生", "身无分文", "凡俗", "凡胎", 28, 0, 10, 72);
        state.abilities.add(new GameState.AbilityRecord("qi-stage", "qi-stage", "练气一层（冥草旁门）",
                "TECHNIQUE", "巅峰", 2, "药力炼化", "已炼化第一缕灵气",
                "持续运转", 0, 0, "NONE", 0, "经脉刺痛"));

        assertEquals("凡俗", state.player.realm);
    }

    @Test
    void appliesMultipleEntityChangesAndAnAuthorizedLocationTransitionAtomically() {
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "market";
        state.sceneTitle = "集市";
        state.currentLocation = "青木镇集市";
        state.player = new GameState.PlayerState("陆沉", "HUMAN", "MALE", 16, 75,
                "凡人", "LONGEVITY", "活下去", "旧债", "凡俗", "凡胎", 60, 0, 6, 0);
        state.threads.add(new GameState.QuestThread("missing-herb", "失踪药材", "进行中", 20));
        LocationAuthority.repairLegacyState(state);
        AgentResult result = new AgentResult(
                "north-gate", "抵达北门", "青木镇北门", 30, "CONTINUE",
                List.of("你离开集市。", "半个时辰后抵达北门。"), List.of("发现两件物品"), List.of(),
                Map.of("condition", "STABLE"), new AgentResult.EventDraft("赶赴北门", "你沿城路抵达北门。", "world"),
                null, null, null, null, new AgentResult.ActionResolution("ACCEPTED", "", "", List.of()),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"),
                new ActionIntent("TRAVEL", null, null, null, "UNKNOWN", List.of(), "LOW", false, List.of()),
                ConflictVerdict.narrativeOnly(), List.of(),
                List.of(
                        new AgentResult.InventoryChange("ADD", "map", "map", "旧地图", "CLUE", "凡物", 1, "标着北门", true),
                        new AgentResult.InventoryChange("ADD", "herb", "herb", "止血草", "MATERIAL", "凡物", 2, "可止血", false)),
                List.of(new AgentResult.AbilityChange("LEARN", "tracking", "tracking", "辨踪", "TECHNIQUE",
                        "入门", 1, "路途观察", "辨认浅近足迹", "依痕迹追踪", 0, 0, "NONE", "无")),
                List.of(new AgentResult.EntityPatch("missing-herb", Map.of("state", "已有线索", "danger", 30))),
                new AgentResult.LocationTransition(state.currentLocationId, "location-north-gate", "青木镇北门",
                        "SETTLEMENT", "WALK", null, "沿城内道路步行抵达"));

        GameState next = new GameEngine(new ObjectMapper().findAndRegisterModules()).apply(state, result);

        assertEquals("青木镇北门", next.currentLocation);
        assertEquals("location-north-gate", next.currentLocationId);
        assertEquals(2, next.inventory.size());
        assertEquals(1, next.abilities.size());
        assertEquals("已有线索", next.threads.getFirst().state);
    }
}

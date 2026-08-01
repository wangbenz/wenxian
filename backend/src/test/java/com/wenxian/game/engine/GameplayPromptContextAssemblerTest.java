package com.wenxian.game.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameplayPromptContextAssemblerTest {

    @Test
    void keepsIndexesButOnlyExpandsEntitiesRelatedToTheAction() throws Exception {
        GameState state = state();
        ActionIntent intent = new ActionIntent("USE_ABILITY", "EXECUTE", false,
                "npc-jiu", "阿九", "凡俗", "CHARACTER_STATE", List.of("ability-eye"),
                "MEDIUM", false, List.of(new ActionIntent.ClaimedFact(
                "ITEM", "item-mirror", "existence", "照影镜", true, "REQUIRED_EXISTING")));

        Map<String, Object> context = new GameplayPromptContextAssembler()
                .assemble(state, "用灵瞳借照影镜观察阿九", intent);
        String json = new ObjectMapper().writeValueAsString(context);

        assertTrue(json.contains("灵瞳的完整规则"));
        assertTrue(json.contains("照影镜的完整来历"));
        assertTrue(json.contains("阿九的当前状态"));
        assertTrue(json.contains("无关功法"));
        assertTrue(json.contains("闲置木牌"));
        assertFalse(json.contains("无关功法的冗长规则"));
        assertFalse(json.contains("闲置木牌的冗长来历"));
        assertEquals(state.narrative, context.get("currentNarrative"));
    }

    @Test
    void omitsLargeWorldProfilesAndLegacyNumericVitality() throws Exception {
        GameState state = state();
        state.world.regionProfiles.add(new GameState.RegionProfile(
                "east", "东陆", "不会每回合重复的地域长描述", "青梧门", "舟行"));

        Map<String, Object> context = new GameplayPromptContextAssembler().assemble(state, "继续观察", null);
        String json = new ObjectMapper().writeValueAsString(context);

        @SuppressWarnings("unchecked")
        Map<String, Object> player = (Map<String, Object>) context.get("player");
        assertFalse(player.containsKey("vitality"));
        assertFalse(player.containsKey("silver"));
        assertFalse(json.contains("不会每回合重复的地域长描述"));
        assertTrue(json.contains("东陆"));
    }

    private GameState state() {
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "temple";
        state.sceneTitle = "旧庙";
        state.currentLocation = "青木镇";
        state.currentLocationId = "qingmu";
        state.worldYear = 3001;
        state.day = 2;
        state.minute = 600;
        state.turn = 8;
        state.player = new GameState.PlayerState("陆沉", "HUMAN", "MALE", 16, 75,
                "凡人", "LONGEVITY", "活下去", "旧债", "凡俗", "凡胎", 60, 20, 6, 0);
        state.wallet = new GameState.Wallet(20, 0, 0, 0);
        state.narrative = List.of("第一段旧庙风声。", "第二段阿九推门。", "第三段灯影摇晃。");
        state.world = new GameState.WorldProfile();
        state.world.id = "xuanxu";
        state.world.title = "玄墟界";
        state.world.regions = List.of("东陆", "北荒");
        state.characters.add(new GameState.CharacterRecord("npc-jiu", "阿九", "药童",
                "凡俗", "STABLE", "常人", "阿九的当前状态", "青木镇", "相识", 600));
        state.characters.add(new GameState.CharacterRecord("npc-away", "周远", "行商",
                "凡俗", "STABLE", "常人", "周远的冗长状态", "北荒", "陌生", 100));
        state.inventory.add(new GameState.InventoryItem("item-mirror", "mirror", "照影镜",
                "TOOL", "凡物", 1, "照影镜的完整来历", false));
        state.inventory.add(new GameState.InventoryItem("item-token", "token", "闲置木牌",
                "TOOL", "凡物", 1, "闲置木牌的冗长来历", false));
        state.abilities.add(new GameState.AbilityRecord("ability-eye", "spirit-eye", "灵瞳",
                "TALENT", "入门", 10, "命格", "灵瞳的完整规则", "每日一次", 1, 1,
                "DAILY", 1, "目眩"));
        state.abilities.add(new GameState.AbilityRecord("ability-other", "other", "无关功法",
                "TECHNIQUE", "入门", 5, "旧书", "无关功法的冗长规则", "吐纳", 0, 0,
                "NONE", 1, "无"));
        state.knownLocations.add(new GameState.LocationRecord("qingmu", "青木镇", "SETTLEMENT", null, 0));
        return state;
    }
}

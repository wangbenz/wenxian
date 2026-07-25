package com.wenxian.game.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeepSeekGameplayAgentServiceTest {
    @Test
    void validatesModelPatchBeforeItCanReachGameEngine() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorldGenerationService client = new WorldGenerationService(
                mapper, RestClient.builder(), "test-key", "https://api.deepseek.com", "deepseek-v4-pro");
        DeepSeekGameplayAgentService agent = new DeepSeekGameplayAgentService(mapper, client, new AgentToolRegistry(mapper));
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "opening";
        state.sceneTitle = "旧屋";
        state.currentLocation = "柳溪村";
        state.player = new GameState.PlayerState("林砚", "HUMAN", "MALE", 16, 75,
                "凡人", "KINSHIP", "活下去", "旧债", "凡俗", "未测", 60, 20, 7, 30);

        AgentResult raw = new AgentResult("next", "雨巷", "柳溪村", 9_999, "CONTINUE",
                List.of("你踏入雨里。", "身后有人关上了门。", "檐水落在肩头。", "巷口灯影摇晃。", "脚步越来越近。", "你必须决定去向。"), List.of("追兵接近"),
                List.of(new GameState.StoryChoice("run", "逃", "钻入暗巷", "danger"),
                        new GameState.StoryChoice("wait", "等", "听清脚步", "default")),
                Map.of("vitality", 120, "illegal", "ignored"),
                new AgentResult.EventDraft("踏入雨巷", "你离开旧屋。", "decision"),
                new AgentResult.EntityPatch("invented-character", Map.of("state", "出现")), null,
                null, null, new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"));

        AgentResult safe = agent.sanitize(raw, state);

        assertEquals(2880, safe.elapsed());
        assertEquals(120, safe.playerPatch().get("vitality"));
        assertNull(safe.characterPatch());
        assertEquals(2, safe.choices().size());
    }

    @Test
    void refusesAnAcceptedTalentDeclarationWithoutAbilityPersistence() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorldGenerationService client = new WorldGenerationService(
                mapper, RestClient.builder(), "test-key", "https://api.deepseek.com", "deepseek-v4-pro");
        DeepSeekGameplayAgentService agent = new DeepSeekGameplayAgentService(mapper, client, new AgentToolRegistry(mapper));
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "opening";
        state.sceneTitle = "旧屋";
        state.currentLocation = "柳溪村";
        state.player = new GameState.PlayerState("林砚", "HUMAN", "MALE", 16, 75,
                "凡人", "KINSHIP", "活下去", "旧债", "凡俗", "未测", 60, 20, 7, 30);
        AgentResult raw = new AgentResult("opening", "旧屋", "柳溪村", 10, "CONTINUE",
                List.of("一", "二", "三", "四", "五", "六"), List.of("天赋待定"),
                List.of(new GameState.StoryChoice("look", "观察", "查看窗外", "default"),
                        new GameState.StoryChoice("leave", "离开", "走出旧屋", "default")),
                Map.of(), new AgentResult.EventDraft("自述天赋", "你声称自己可以透视。", "decision"),
                null, null, null, null,
                new AgentResult.DeclarationResolution("ABILITY", "ACCEPTED", "开局补完"));

        assertThrows(IllegalArgumentException.class, () -> agent.sanitize(raw, state));
    }
}

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

    @Test
    void completesMissingRealmPatchFromExplicitBreakthroughEvidence() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorldGenerationService client = new WorldGenerationService(
                mapper, RestClient.builder(), "test-key", "https://api.deepseek.com", "deepseek-v4-pro");
        DeepSeekGameplayAgentService agent = new DeepSeekGameplayAgentService(mapper, client, new AgentToolRegistry(mapper));
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "cave";
        state.sceneTitle = "石洞";
        state.currentLocation = "东陆边城";
        state.player = new GameState.PlayerState("宁临渊", "HUMAN", "MALE", 16, 75,
                "乞儿", "LONGEVITY", "求长生", "身无分文", "凡俗", "凡胎", 28, 0, 10, 72);

        AgentResult raw = new AgentResult("breach", "初次引气", "石洞", 240, "CONTINUE",
                List.of("药力沿经脉散开。", "你忍住剧痛。", "第一缕灵气被纳入丹田。", "周围尘埃缓缓落下。", "你的气息终于稳定。", "你已踏入引气。"),
                List.of("突破引气成功"),
                List.of(new GameState.StoryChoice("rest", "稳固气息", "先处理体内暗伤", "default"),
                        new GameState.StoryChoice("leave", "离开石洞", "冒险避开追兵", "danger")),
                Map.of("vitality", 28), new AgentResult.EventDraft("初次引气", "你完成第一次引气。", "gain"),
                null, null, null,
                new AgentResult.AbilityChange("LEARN", "qi-stage", "qi-stage", "练气一层（旁门）",
                        "TECHNIQUE", "入门", 1, "药力", "炼化第一缕灵气", "运转灵气", 0, 0, "NONE", "经脉刺痛"),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"));

        AgentResult safe = agent.sanitize(raw, state);

        assertEquals("引气", safe.playerPatch().get("realm"));
    }

    @Test
    void normalizesCommonQiCultivationAliasToCanonicalRealm() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorldGenerationService client = new WorldGenerationService(
                mapper, RestClient.builder(), "test-key", "https://api.deepseek.com", "deepseek-v4-pro");
        DeepSeekGameplayAgentService agent = new DeepSeekGameplayAgentService(mapper, client, new AgentToolRegistry(mapper));
        GameState state = new GameState();
        state.phase = "playing";
        state.player = new GameState.PlayerState("宁临渊", "HUMAN", "MALE", 16, 75,
                "乞儿", "LONGEVITY", "求长生", "身无分文", "凡俗", "凡胎", 28, 0, 10, 72);

        AgentResult raw = new AgentResult("breach", "初次引气", "石洞", 240, "CONTINUE",
                List.of("一", "二", "三", "四", "五", "六"), List.of("境界稳固"),
                List.of(new GameState.StoryChoice("rest", "稳固", "先稳住气息", "default"),
                        new GameState.StoryChoice("leave", "离开", "立即离开", "danger")),
                Map.of("realm", "练气一层"), new AgentResult.EventDraft("初次引气", "你完成第一次引气。", "gain"),
                null, null, null, null,
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"));

        assertEquals("引气", agent.sanitize(raw, state).playerPatch().get("realm"));
    }
}

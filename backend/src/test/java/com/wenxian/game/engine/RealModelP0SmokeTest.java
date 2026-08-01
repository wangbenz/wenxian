package com.wenxian.game.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.config.RuntimeAiConfigService;
import com.wenxian.game.model.ApiModels.TurnRequest;
import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Manually enabled smoke coverage for the real provider; excluded from ordinary local/CI runs. */
class RealModelP0SmokeTest {
    @Test
    void realModelKeepsOwnedAbilitiesAndExplicitTimeConsistent() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "set DEEPSEEK_API_KEY to run the real-model smoke test");

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorldGenerationService model = new WorldGenerationService(
                mapper, RestClient.builder(), apiKey, "https://api.deepseek.com", "deepseek-v4-flash");
        ActionIntentAgentService intentAgent = new ActionIntentAgentService(mapper, model);
        ConflictAdjudicationService adjudicator = new ConflictAdjudicationService(intentAgent);
        DeepSeekGameplayAgentService narrative = new DeepSeekGameplayAgentService(
                mapper, model, new AgentToolRegistry(mapper));
        RuntimeAiConfigService.RuntimeConfig config = new RuntimeAiConfigService.RuntimeConfig(
                "DEEPSEEK", "deepseek-v4-flash", "V4 Flash", apiKey);

        GameState abilityState = state("smoke-ability");
        abilityState.abilities.add(new GameState.AbilityRecord(
                "ability-wind-vacuum", "wind-vacuum", "风息掌控", "TALENT", "入门", 20,
                "命格", "能够牵引近处气流。", "只影响身周气流。", 1, 1,
                "DAILY", 0, "使用后精神疲惫"));
        String abilityAction = "施展风息掌控，将洞口散乱的阴风压回石缝";
        ActionAdjudication abilityAdjudication = adjudicator.adjudicate(textRequest("smoke-ability-request"),
                abilityAction, abilityState, config, "SMOKE_TEST");
        assertTrue(abilityAdjudication.accepted(), abilityAdjudication.actionResolution().reason());
        assertEquals(List.of("ability-wind-vacuum"), abilityAdjudication.intent().usedAbilityIds());
        AgentTurn abilityTurn = narrative.resolve(abilityAction, abilityState, List.of(), config,
                "SMOKE_TEST", abilityAdjudication);
        report("ability", abilityTurn);
        assertEquals("ACCEPTED", abilityTurn.result().actionResolution().result());
        GameState abilityNext = new GameEngine(mapper).apply(abilityState, abilityTurn.result());
        abilityAdjudication.intent().usedAbilityIds().forEach(id -> new GameEngine(mapper).consumeAbility(abilityNext, id));
        assertEquals(1, abilityNext.turn);
        assertEquals(0, abilityNext.abilities.getFirst().remainingUses);

        GameState timeState = state("smoke-time");
        String timeAction = "闭关一个月，专心温养气息，不尝试突破境界";
        ActionAdjudication timeAdjudication = adjudicator.adjudicate(textRequest("smoke-time-request"),
                timeAction, timeState, config, "SMOKE_TEST");
        assertTrue(timeAdjudication.accepted(), timeAdjudication.actionResolution().reason());
        assertEquals(30 * 1440, timeAdjudication.intent().requestedDurationMinutes());
        AgentTurn timeTurn = narrative.resolve(timeAction, timeState, List.of(), config,
                "SMOKE_TEST", timeAdjudication);
        report("time", timeTurn);
        assertNotNull(timeTurn.result().timeResolution());
        assertEquals(timeTurn.result().elapsed(), timeTurn.result().timeResolution().actualMinutes());
        assertTrue(timeTurn.result().timeResolution().completed()
                        ? timeTurn.result().elapsed() == 30 * 1440
                        : timeTurn.result().elapsed() < 30 * 1440
                                && !timeTurn.result().timeResolution().interruptionReason().isBlank(),
                "explicit duration must either finish or provide a concrete interruption");

        GameState establishedState = establishedState();
        String establishedAction = "与阿九交谈，追问三年前失踪的采药人";
        ActionIntent establishedIntent = new ActionIntent("NEGOTIATE", "ATTEMPT", false,
                "npc-jiu", "阿九", "凡俗", "CHARACTER_STATE", List.of(),
                "LOW", false, List.of());
        AgentTurn establishedTurn = narrative.resolve(establishedAction, establishedState,
                List.of(Map.of("role", "user", "content", "【此前命卷提要】\n" +
                        "三年前你离开旧村，此后在青木镇追查父亲失踪；最近得知回春堂曾向黑松岭派出采药人。")),
                config, "SMOKE_TEST", ActionAdjudication.accepted(
                        establishedIntent, ConflictVerdict.narrativeOnly()));
        report("established-save", establishedTurn);
        assertEquals("ACCEPTED", establishedTurn.result().actionResolution().result());
    }

    private void report(String scenario, AgentTurn turn) {
        System.out.printf("REAL_MODEL_METRIC scenario=%s prompt=%d completion=%d latencyMs=%d model=%s%n",
                scenario, turn.promptTokens(), turn.completionTokens(), turn.latencyMs(), turn.model());
    }

    private TurnRequest textRequest(String id) {
        return new TurnRequest(id, 0, "TEXT", null, null, "smoke");
    }

    private GameState state(String saveId) {
        GameState state = new GameState();
        state.saveId = saveId;
        state.phase = "playing";
        state.sceneId = "stone-cave";
        state.sceneTitle = "避风石洞";
        state.currentLocation = "青木镇外黑松岭";
        state.currentLocationId = "black-pine-ridge";
        state.worldYear = 3001;
        state.day = 1;
        state.minute = 480;
        state.player = new GameState.PlayerState("测试者", "HUMAN", "MALE", 18, 75,
                "山野散修", "LONGEVITY", "寻找修行之路", "旧债", "引气", "凡骨",
                60, 30, 6, 10);
        state.player.condition = "STABLE";
        state.narrative = List.of("你暂时藏身黑松岭的避风石洞，洞外阴风不止。", "此地无人打扰，适合短暂休整。 ");
        state.choices = List.of(
                new GameState.StoryChoice("rest", "休整", "先稳住气息", "default"),
                new GameState.StoryChoice("leave", "离开", "返回山道", "default"));
        return state;
    }

    private GameState establishedState() {
        GameState state = state("smoke-established");
        state.turn = 36;
        state.narrative = List.of(
                "雨歇后的青木镇仍有潮气，你循着回春堂的旧账来到废庙。",
                "阿九守在残灯边，承认三年前曾见过一辆来自黑松岭的黑布车。",
                "他说车里有人敲了三下木板，却在你继续追问时闭口不言。"
        );
        state.characters.add(new GameState.CharacterRecord("npc-jiu", "阿九", "药童",
                "凡俗", "STABLE", "常人", "知道黑布车旧事但仍有顾忌",
                state.currentLocation, "互有试探", 480));
        for (int index = 0; index < 18; index++) {
            state.characters.add(new GameState.CharacterRecord("npc-remote-" + index, "远客" + index,
                    "旧日行商", "UNKNOWN", "STABLE", "未明",
                    "这是一段不会因本轮行动而展开的远方人物详细状态".repeat(3),
                    "南溟群岛", "曾有一面之缘", 100));
        }
        for (int index = 0; index < 14; index++) {
            state.inventory.add(new GameState.InventoryItem("item-" + index, "definition-" + index,
                    "旧物" + index, "MATERIAL", "凡物", 1,
                    "这是一段只在相关行动中才应展开的物品来历".repeat(5), false));
        }
        for (int index = 0; index < 10; index++) {
            state.abilities.add(new GameState.AbilityRecord("ability-" + index, "ability-definition-" + index,
                    "法门" + index, "TECHNIQUE", "入门", 10, "旧传承",
                    "这是一段只在施展该法门时才应展开的能力说明".repeat(5),
                    "对应法门规则".repeat(5), 0, 0, "NONE", 1, "无"));
        }
        for (int index = 0; index < 20; index++) {
            state.knownLocations.add(new GameState.LocationRecord("location-" + index,
                    "旧地" + index, "REGIONAL", null, index));
        }
        return state;
    }
}

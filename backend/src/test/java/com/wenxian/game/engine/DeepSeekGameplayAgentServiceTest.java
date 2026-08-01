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
import static org.junit.jupiter.api.Assertions.assertFalse;

class DeepSeekGameplayAgentServiceTest {
    @Test
    void authoritativePromptContextDoesNotExposeLegacyNumericVitality() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorldGenerationService client = new WorldGenerationService(
                mapper, RestClient.builder(), "test-key", "https://api.deepseek.com", "deepseek-v4-pro");
        DeepSeekGameplayAgentService agent = new DeepSeekGameplayAgentService(mapper, client, new AgentToolRegistry(mapper));
        GameState state = new GameState();
        state.player = new GameState.PlayerState("林砚", "HUMAN", "MALE", 16, 75,
                "凡人", "KINSHIP", "活下去", "旧债", "凡俗", "未测", 3, 20, 7, 30);
        state.player.condition = "STABLE";

        @SuppressWarnings("unchecked")
        Map<String, Object> player = (Map<String, Object>) agent.currentStateContext(state).get("player");

        assertFalse(player.containsKey("vitality"));
        assertFalse(player.containsKey("silver"));
        assertEquals("STABLE", player.get("condition"));
    }

    @Test
    void rejectsLegacyAbsoluteSilverAndAcceptsValidatedCurrencyDeltas() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorldGenerationService client = new WorldGenerationService(
                mapper, RestClient.builder(), "test-key", "https://api.deepseek.com", "deepseek-v4-pro");
        DeepSeekGameplayAgentService agent = new DeepSeekGameplayAgentService(mapper, client, new AgentToolRegistry(mapper));
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "market";
        state.sceneTitle = "集市";
        state.currentLocation = "青木镇";
        state.player = new GameState.PlayerState("陆沉", "HUMAN", "MALE", 16, 75,
                "凡人", "LONGEVITY", "活下去", "旧债", "凡俗", "凡胎", 60, 20, 6, 0);
        AgentResult base = new AgentResult("market-end", "药摊", "青木镇", 10, "CONTINUE",
                List.of("一", "二"), List.of("交易完成"),
                List.of(new GameState.StoryChoice("leave", "离开", "收好钱离开", "default"),
                        new GameState.StoryChoice("look", "查看", "看看别的摊位", "default")),
                Map.of("condition", "STABLE"), new AgentResult.EventDraft("卖出药材", "换得六十文。", "gain"),
                null, null, null, null, acceptedAction(),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"));

        AgentResult safe = agent.sanitize(base.withCurrencyChanges(List.of(
                new AgentResult.CurrencyChange(CurrencyAuthority.COPPER_WEN, 60, "出售药材")
        )), state);
        assertEquals(60, safe.currencyChanges().getFirst().amount());

        AgentResult illegal = new AgentResult("market-end", "药摊", "青木镇", 10, "CONTINUE",
                List.of("一", "二"), List.of("交易完成"), base.choices(), Map.of("silver", 80),
                base.event(), null, null, null, null, acceptedAction(), base.declarationResolution());
        assertThrows(CurrencyAuthority.CurrencyValidationException.class, () -> agent.sanitize(illegal, state));
    }

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

        AgentResult raw = new AgentResult("next", "雨巷", "柳溪村",
                DeepSeekGameplayAgentService.MAX_ELAPSED_MINUTES + 1, "CONTINUE",
                List.of("你踏入雨里。", "身后有人关上了门。", "檐水落在肩头。", "巷口灯影摇晃。", "脚步越来越近。", "你必须决定去向。"), List.of("追兵接近"),
                List.of(new GameState.StoryChoice("run", "逃", "钻入暗巷", "danger"),
                        new GameState.StoryChoice("wait", "等", "听清脚步", "default")),
                Map.of("vitality", 120, "illegal", "ignored"),
                new AgentResult.EventDraft("踏入雨巷", "你离开旧屋。", "decision"),
                null, null,
                null, null, acceptedAction(), new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"));

        AgentResult safe = agent.sanitize(raw, state);

        assertEquals(DeepSeekGameplayAgentService.MAX_ELAPSED_MINUTES, safe.elapsed());
        assertNull(safe.playerPatch().get("vitality"));
        assertEquals("STABLE", safe.playerPatch().get("condition"));
        assertNull(safe.characterPatch());
        assertEquals(2, safe.choices().size());
    }

    @Test
    void rejectsAnUnknownLegacyCharacterPatchInsteadOfSilentlyDroppingIt() {
        DeepSeekGameplayAgentService agent = agent();
        GameState state = playableState();
        AgentResult raw = baseResult(state, new AgentResult.EntityPatch(
                "invented-character", Map.of("state", "出现")), null, null);

        assertThrows(AuthoritativeEntityResolver.EntityValidationException.class,
                () -> agent.sanitize(raw, state));
    }

    @Test
    void canonicalizesExistingEntityChangesByDefinitionIdOrName() {
        DeepSeekGameplayAgentService agent = agent();
        GameState state = playableState();
        state.inventory.add(new GameState.InventoryItem("item-red-herb", "red-herb", "暗红三叶草",
                "MATERIAL", "凡物", 2, "药草", false));
        state.abilities.add(new GameState.AbilityRecord("ability-wind-vacuum", "wind-vacuum", "风息掌控",
                "TALENT", "入门", 10, "命格", "控风", "引风", 1, 1, "DAILY", 0, "疲惫"));
        state.characters.add(new GameState.CharacterRecord("npc-a-jiu", "阿九", "药童",
                "凡俗", "STABLE", "常人", "存活", state.currentLocation, "相识", 0));

        AgentResult raw = baseResult(state, null,
                List.of(new AgentResult.InventoryChange("REMOVE", "wrong-item-id", "red-herb",
                        "暗红三叶草", "MATERIAL", "凡物", 1, "服下一片", false)),
                List.of(new AgentResult.AbilityChange("UPDATE", "wrong-ability-id", "wrong-definition",
                        "风息掌控", "TALENT", "小成", 30, "命格", "控风更稳", "引风",
                        1, 1, "DAILY", "疲惫")));
        raw = new AgentResult(raw.sceneId(), raw.sceneTitle(), raw.currentLocation(), raw.elapsed(), raw.outcome(),
                raw.narrative(), raw.signals(), raw.choices(), raw.playerPatch(), raw.event(), raw.characterPatch(),
                raw.threadPatch(), raw.inventoryChange(), raw.abilityChange(), raw.actionResolution(),
                raw.declarationResolution(), null, null,
                List.of(new AgentResult.CharacterChange("UPDATE", "wrong-npc-id", "阿九", "药童",
                        "凡俗", "STABLE", "常人", "同行", state.currentLocation, "信任")),
                raw.inventoryChanges(), raw.abilityChanges(), List.of(), null, null, List.of());

        AgentResult safe = agent.sanitize(raw, state);

        assertEquals("item-red-herb", safe.inventoryChanges().getFirst().id());
        assertEquals("ability-wind-vacuum", safe.abilityChanges().getFirst().id());
        assertEquals("npc-a-jiu", safe.characterChanges().getFirst().id());
    }

    @Test
    void rejectsUpdatesThatCannotBeBoundToAuthoritativeState() {
        DeepSeekGameplayAgentService agent = agent();
        GameState state = playableState();
        AgentResult raw = baseResult(state, null,
                List.of(new AgentResult.InventoryChange("UPDATE", "missing-item", "missing-item",
                        "不存在的物品", "MATERIAL", "凡物", 1, "修改", false)), null);

        assertThrows(AuthoritativeEntityResolver.EntityValidationException.class,
                () -> agent.sanitize(raw, state));
    }

    @Test
    void treatsAChangedNonTravelLocationLabelAsSceneDetail() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorldGenerationService client = new WorldGenerationService(
                mapper, RestClient.builder(), "test-key", "https://api.deepseek.com", "deepseek-v4-pro");
        DeepSeekGameplayAgentService agent = new DeepSeekGameplayAgentService(mapper, client, new AgentToolRegistry(mapper));
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "market";
        state.sceneTitle = "地下黑市";
        state.currentLocation = "枫林渡码头·地下黑市";
        LocationAuthority.repairLegacyState(state);
        state.player = new GameState.PlayerState("古月方源", "HUMAN", "MALE", 20, 75,
                "散修", "LONGEVITY", "问道", "旧债", "筑基", "凡骨", 60, 20, 7, 30);
        AgentResult raw = new AgentResult("weapon-stall", null, "黑市深处的兵器摊", 20, "CONTINUE",
                List.of("一", "二", "三", "四", "五", "六"), List.of("发现旧剑"),
                List.of(new GameState.StoryChoice("look", "查看", "辨认旧剑", "default"),
                        new GameState.StoryChoice("leave", "离开", "回到甬道", "default")),
                Map.of(), new AgentResult.EventDraft("发现旧剑", "你在兵器摊前停步。", "decision"),
                null, null, null, null, acceptedAction(),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"));

        AgentResult safe = agent.sanitize(raw, state);

        assertEquals("枫林渡码头·地下黑市", safe.currentLocation());
        assertEquals("黑市深处的兵器摊", safe.sceneTitle());
        assertNull(safe.locationTransition());
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
                acceptedAction(),
                new AgentResult.DeclarationResolution("ABILITY", "ACCEPTED", "开局补完"));

        assertThrows(IllegalArgumentException.class, () -> agent.sanitize(raw, state));
    }

    @Test
    void appliesOnlyAnExplicitAuthorizedRealmTransition() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorldGenerationService client = new WorldGenerationService(
                mapper, RestClient.builder(), "test-key", "https://api.deepseek.com", "deepseek-v4-pro");
        DeepSeekGameplayAgentService agent = new DeepSeekGameplayAgentService(mapper, client, new AgentToolRegistry(mapper));
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "cave";
        state.sceneTitle = "石洞";
        state.currentLocation = "石洞";
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
                acceptedAction(),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"))
                .withRealmTransition(new AgentResult.RealmTransition(
                        "SUCCESS", "凡俗", "引气", "本轮明确完成引气入体"));

        AgentResult safe = agent.sanitize(raw, state, breakthroughIntent());

        assertNull(safe.playerPatch().get("realm"));
        assertEquals("引气", safe.realmTransition().toRealm());
    }

    @Test
    void rejectsRealmInsideTheGenericPlayerPatch() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorldGenerationService client = new WorldGenerationService(
                mapper, RestClient.builder(), "test-key", "https://api.deepseek.com", "deepseek-v4-pro");
        DeepSeekGameplayAgentService agent = new DeepSeekGameplayAgentService(mapper, client, new AgentToolRegistry(mapper));
        GameState state = new GameState();
        state.phase = "playing";
        state.currentLocation = "石洞";
        state.player = new GameState.PlayerState("宁临渊", "HUMAN", "MALE", 16, 75,
                "乞儿", "LONGEVITY", "求长生", "身无分文", "凡俗", "凡胎", 28, 0, 10, 72);

        AgentResult raw = new AgentResult("breach", "初次引气", "石洞", 240, "CONTINUE",
                List.of("一", "二", "三", "四", "五", "你已踏入引气。"), List.of("境界稳固"),
                List.of(new GameState.StoryChoice("rest", "稳固", "先稳住气息", "default"),
                        new GameState.StoryChoice("leave", "离开", "立即离开", "danger")),
                Map.of("realm", "练气一层"), new AgentResult.EventDraft("初次引气", "你完成第一次引气。", "gain"),
                null, null, null, null,
                acceptedAction(),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"));

        assertThrows(RealmProgression.RealmValidationException.class,
                () -> agent.sanitize(raw, state, breakthroughIntent()));
    }

    @Test
    void rejectsAStructuredRealmTransitionThatSkipsRequiredRealms() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorldGenerationService client = new WorldGenerationService(
                mapper, RestClient.builder(), "test-key", "https://api.deepseek.com", "deepseek-v4-pro");
        DeepSeekGameplayAgentService agent = new DeepSeekGameplayAgentService(mapper, client, new AgentToolRegistry(mapper));
        GameState state = new GameState();
        state.phase = "playing";
        state.currentLocation = "石洞";
        state.player = new GameState.PlayerState("宁临渊", "HUMAN", "MALE", 16, 75,
                "乞儿", "LONGEVITY", "求长生", "身无分文", "凡俗", "凡胎", 28, 0, 10, 72);
        AgentResult raw = new AgentResult("breach", "妄求元婴", "石洞", 30, "CONTINUE",
                List.of("一", "二", "三", "四", "五", "你宣称自己已踏入元婴。"), List.of("元婴已成"),
                List.of(new GameState.StoryChoice("rest", "稳固", "先稳住气息", "default"),
                        new GameState.StoryChoice("leave", "离开", "立即离开", "danger")),
                Map.of(), new AgentResult.EventDraft("妄求元婴", "试图跨境。", "risk"),
                null, null, null, null, acceptedAction(),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"))
                .withRealmTransition(new AgentResult.RealmTransition(
                        "SUCCESS", "凡俗", "元婴", "玩家试图越级突破"));

        assertThrows(RealmProgression.RealmValidationException.class,
                () -> agent.sanitize(raw, state, breakthroughIntent()));
    }

    @Test
    void npcAndFutureRealmMentionsNeverChangePlayerRealm() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorldGenerationService client = new WorldGenerationService(
                mapper, RestClient.builder(), "test-key", "https://api.deepseek.com", "deepseek-v4-pro");
        DeepSeekGameplayAgentService agent = new DeepSeekGameplayAgentService(mapper, client, new AgentToolRegistry(mapper));
        GameState state = new GameState();
        state.phase = "playing";
        state.currentLocation = "苍梧剑宗";
        state.player = new GameState.PlayerState("顾砚舟", "HUMAN", "MALE", 16, 75,
                "外门弟子", "LONGEVITY", "求长生", "旧债", "引气", "凡胎", 60, 0, 10, 72);
        AgentResult raw = new AgentResult("lesson", "请教剑法", "苍梧剑宗", 60, "CONTINUE",
                List.of("秦霜提醒你，将来突破筑基时剑气混杂会成为隐患。",
                        "孙长老说韩雪衣本有望在三十岁前踏入金丹境。"),
                List.of("你只是得到两句提醒，没有突破"),
                List.of(new GameState.StoryChoice("leave", "离开", "结束请教", "default"),
                        new GameState.StoryChoice("ask", "追问", "继续询问", "default")),
                Map.of(), new AgentResult.EventDraft("请教剑法", "你向前辈请教修行疑难。", "decision"),
                null, null, null, null, acceptedAction(),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"));

        AgentResult safe = agent.sanitize(raw, state,
                new ActionIntent("NEGOTIATE", null, null, null, "UNKNOWN", List.of(),
                        "LOW", false, List.of()));

        assertNull(safe.playerPatch().get("realm"));
        assertNull(safe.realmTransition());
    }

    @Test
    void keepsARejectedActionOutsideTheWorldState() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorldGenerationService client = new WorldGenerationService(
                mapper, RestClient.builder(), "test-key", "https://api.deepseek.com", "deepseek-v4-pro");
        DeepSeekGameplayAgentService agent = new DeepSeekGameplayAgentService(mapper, client, new AgentToolRegistry(mapper));
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "alley";
        state.sceneTitle = "后巷";
        state.currentLocation = "青木镇";
        AgentResult raw = new AgentResult(null, null, null, 30, "CONTINUE",
                null, null, null, null, null, null, null, null, null,
                new AgentResult.ActionResolution("REJECTED", "此念不可行", "当前境界不足。",
                        List.of("寻找引气法门", "打听灵根测试")),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"));

        AgentResult safe = agent.sanitize(raw, state);

        assertEquals(0, safe.elapsed());
        assertEquals("alley", safe.sceneId());
        assertEquals(List.of(), safe.narrative());
        assertEquals(Map.of(), safe.playerPatch());
    }

    @Test
    void javaVerdictOverridesDramaticButContradictoryModelOutcome() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorldGenerationService client = new WorldGenerationService(
                mapper, RestClient.builder(), "test-key", "https://api.deepseek.com", "deepseek-v4-pro");
        DeepSeekGameplayAgentService agent = new DeepSeekGameplayAgentService(mapper, client, new AgentToolRegistry(mapper));
        GameState state = new GameState();
        state.phase = "playing";
        state.currentLocation = "山门";
        state.player = new GameState.PlayerState("宁临渊", "HUMAN", "MALE", 30, 300,
                "散修", "LONGEVITY", "问道", "旧债", "元婴", "道体", 60, 0, 8, 0);

        AgentResult raw = new AgentResult("fight", "山门", "山门", 20, "DEAD",
                List.of("一", "二", "三", "四", "五", "六"), List.of("遭遇筑基守卫"),
                List.of(new GameState.StoryChoice("leave", "离开", "退出山门", "default"),
                        new GameState.StoryChoice("ask", "询问", "查明来历", "default")),
                Map.of("vitality", 2, "condition", "SEVERE"),
                new AgentResult.EventDraft("山门交锋", "你与守卫交锋。", "risk"),
                null, null, null, null, acceptedAction(),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"));
        ConflictVerdict verdict = new ConflictVerdict("DOMINANT_SUCCESS", "OVERWHELMING_ADVANTAGE",
                "STABLE", false, List.of("元婴对筑基"), List.of("主角不得落败"));

        AgentResult safe = agent.sanitize(raw, state, verdict);

        assertEquals("CONTINUE", safe.outcome());
        assertEquals("STABLE", safe.playerPatch().get("condition"));
        assertNull(safe.playerPatch().get("vitality"));
        assertEquals(verdict, safe.adjudication());
    }

    @Test
    void exactRequestedDurationAdvancesTheWholePeriod() {
        DeepSeekGameplayAgentService agent = agent();
        GameState state = playableState();
        int requested = 3 * 360 * 1440;
        ActionIntent intent = new ActionIntent("CULTIVATE", "ATTEMPT", false,
                null, null, null, "UNKNOWN", List.of(), "LOW", false, List.of(), requested);
        AgentResult raw = baseResult(state, null, null, null).withTimeResolution(
                new AgentResult.TimeResolution(requested, requested, true, null));
        raw = resultWithElapsed(raw, requested);

        AgentResult safe = agent.sanitize(raw, state, intent);

        assertEquals(requested, safe.elapsed());
        assertEquals(requested, safe.timeResolution().actualMinutes());
        assertEquals(true, safe.timeResolution().completed());
    }

    @Test
    void shorterExplicitDurationRequiresAStructuredInterruptionReason() {
        DeepSeekGameplayAgentService agent = agent();
        GameState state = playableState();
        int requested = 30 * 1440;
        ActionIntent intent = new ActionIntent("CULTIVATE", "ATTEMPT", false,
                null, null, null, "UNKNOWN", List.of(), "LOW", false, List.of(), requested);
        AgentResult missingProof = resultWithElapsed(baseResult(state, null, null, null), 1440);

        assertThrows(DeepSeekGameplayAgentService.TimeValidationException.class,
                () -> agent.sanitize(missingProof, state, intent));

        AgentResult interrupted = missingProof.withTimeResolution(
                new AgentResult.TimeResolution(requested, 1440, false, "洞外示警钟响起，被迫出关"));
        AgentResult safe = agent.sanitize(interrupted, state, intent);
        assertEquals(1440, safe.elapsed());
        assertEquals("洞外示警钟响起，被迫出关", safe.timeResolution().interruptionReason());
    }

    private AgentResult.ActionResolution acceptedAction() {
        return new AgentResult.ActionResolution("ACCEPTED", "", "", List.of());
    }

    private ActionIntent breakthroughIntent() {
        return new ActionIntent("CULTIVATE", true, null, null, null, "UNKNOWN",
                List.of(), "HIGH", false, List.of());
    }

    private DeepSeekGameplayAgentService agent() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorldGenerationService client = new WorldGenerationService(
                mapper, RestClient.builder(), "test-key", "https://api.deepseek.com", "deepseek-v4-pro");
        return new DeepSeekGameplayAgentService(mapper, client, new AgentToolRegistry(mapper));
    }

    private GameState playableState() {
        GameState state = new GameState();
        state.phase = "playing";
        state.sceneId = "alley";
        state.sceneTitle = "后巷";
        state.currentLocation = "青木镇";
        state.player = new GameState.PlayerState("陆沉", "HUMAN", "MALE", 16, 75,
                "凡人", "LONGEVITY", "活下去", "旧债", "凡俗", "凡胎", 60, 20, 6, 0);
        return state;
    }

    private AgentResult baseResult(GameState state, AgentResult.EntityPatch characterPatch,
                                   List<AgentResult.InventoryChange> inventoryChanges,
                                   List<AgentResult.AbilityChange> abilityChanges) {
        return new AgentResult("alley-next", "后巷", state.currentLocation, 10, "CONTINUE",
                List.of("一", "二"), List.of("局势推进"),
                List.of(new GameState.StoryChoice("leave", "离开", "先离开后巷", "default"),
                        new GameState.StoryChoice("ask", "询问", "继续打听", "default")),
                Map.of(), new AgentResult.EventDraft("后巷余波", "事情有了变化。", "decision"),
                characterPatch, null, null, null, acceptedAction(),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明"),
                null, null, List.of(), inventoryChanges == null ? List.of() : inventoryChanges,
                abilityChanges == null ? List.of() : abilityChanges, List.of(), null, null, List.of());
    }

    private AgentResult resultWithElapsed(AgentResult source, int elapsed) {
        return new AgentResult(source.sceneId(), source.sceneTitle(), source.currentLocation(), elapsed,
                source.outcome(), source.narrative(), source.signals(), source.choices(), source.playerPatch(),
                source.event(), source.characterPatch(), source.threadPatch(), source.inventoryChange(),
                source.abilityChange(), source.actionResolution(), source.declarationResolution(),
                source.actionIntent(), source.adjudication(), source.characterChanges(), source.inventoryChanges(),
                source.abilityChanges(), source.threadChanges(), source.locationTransition(), source.realmTransition(),
                source.currencyChanges(), source.timeResolution());
    }
}

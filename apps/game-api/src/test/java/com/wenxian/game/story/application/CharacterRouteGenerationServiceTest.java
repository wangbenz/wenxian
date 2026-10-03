package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.credit.PublicTurnLimitException;
import com.wenxian.game.story.agent.ai.CharacterCreationAi.CharacterRouteDraft;
import com.wenxian.game.story.agent.ai.CharacterCreationAi.RouteDraft;
import com.wenxian.game.story.agent.ai.CharacterCreationAi.OpeningSceneDraft;
import com.wenxian.game.story.agent.ai.CharacterCreationAi.OpeningChoiceDraft;
import com.wenxian.game.story.agent.ai.CharacterRouteGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CharacterRouteGenerationServiceTest {

    @Test
    void sendsWorldAndPlayerContextThenAssignsStableCandidateIds() {
        AtomicReference<String> context = new AtomicReference<>();
        CharacterRouteGenerator generator = (userId, requestId, json) -> {
            context.set(json);
            return new CharacterRouteDraft(List.of(
                    route("石中初醒", "古砚生灵", "你在矿洞中醒来"),
                    route("客栈旧契", "石狮化灵", "你看见旧契显形"),
                    route("剑冢余音", "断剑化灵", "你听见盗剑者逼近")
            ));
        };
        CharacterRouteGenerationService service = new CharacterRouteGenerationService(
                generator, new ObjectMapper());

        var routes = service.generate("user-1", input());

        assertThat(routes).extracting(CharacterRouteGenerationService.CharacterRouteCandidate::id)
                .containsExactly("route-1", "route-2", "route-3");
        assertThat(routes.getFirst().establishedFacts()).containsExactly("古砚生灵已经成立");
        assertThat(routes.getFirst().startingEntities()).hasSize(1);
        assertThat(context.get()).contains("玄墟界", "灵族", "古砚");
        assertThat(routes.getFirst().scene().narrative()).isEqualTo("你在矿洞中醒来，洞外传来脚步声。");
    }

    @Test
    void rejectsIncompleteModelDraftWithoutCreatingPartialCandidates() {
        CharacterRouteGenerationService service = new CharacterRouteGenerationService(
                (userId, requestId, json) -> new CharacterRouteDraft(List.of(
                        route("仅一条", "来历", "你醒来"))), new ObjectMapper());

        assertThatThrownBy(() -> service.generate("user-1", input()))
                .isInstanceOf(GameApiException.class)
                .extracting(error -> ((GameApiException) error).code())
                .isEqualTo("CHARACTER_ROUTE_GENERATION_FAILED");
    }

    @Test
    void preservesExistingPlatformQuotaFailureContract() {
        PublicTurnLimitException quota = new PublicTurnLimitException("额度已用完", 20, "加入测试群", "123456");
        CharacterRouteGenerationService service = new CharacterRouteGenerationService(
                (userId, requestId, json) -> { throw quota; }, new ObjectMapper());

        assertThatThrownBy(() -> service.generate("user-1", input())).isSameAs(quota);
    }

    @Test
    void finalizesOneCandidateFromCompletePlayerInputWithoutAddingAnotherModelCall() {
        AtomicReference<String> context = new AtomicReference<>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var service = new CharacterRouteGenerationService((user, request, json) -> {
            calls.incrementAndGet();
            context.set(json);
            return new CharacterRouteDraft(List.of(route("寻访旧友", "原有来历", "你来到渡口")));
        }, new ObjectMapper());
        var before = input();
        String complete = "甲".repeat(170) + "不准备拜师，寻找失踪同伴";
        var input = new CharacterRouteGenerationService.Input(before.worldPackageId(), before.worldRevision(),
                before.worldName(), before.worldPremise(), before.worldRules(), before.narrativeGuidance(),
                before.openingRegionRef(), before.openingRegionName(), before.playerName(), before.raceId(),
                before.raceName(), before.genderCode(), complete, before.preferenceSeed(), "FINALIZE", "凝灵初期");

        assertThat(service.generate("user-1", input)).hasSize(1);
        assertThat(calls.get()).isOne();
        assertThat(context.get()).contains(complete, "FINALIZE", "凝灵初期");
    }

    @Test
    void rejectsMissingSceneRatherThanSilentlySubstitutingGenericOpening() {
        var incomplete = new RouteDraft("来历", "根源", "眼前局势", List.of("已确认的来历"), List.of(), List.of(), null);
        var service = new CharacterRouteGenerationService((user, request, json) ->
                new CharacterRouteDraft(List.of(incomplete, incomplete, incomplete)), new ObjectMapper());
        assertThatThrownBy(() -> service.generate("user-1", input()))
                .isInstanceOf(GameApiException.class);
    }

    private CharacterRouteGenerationService.Input input() {
        return new CharacterRouteGenerationService.Input("XUANXU", 1, "玄墟界", "仙路真假难辨",
                List.of("事实不可静默改写"), "古典宗门修仙", "region:xuanxu:qingqi", "东陆青岐州",
                "砚青", "race:xuanxu:spirit", "灵族", "FEMALE", "一方古砚化灵", "自由探索");
    }

    private RouteDraft route(String title, String origin, String opening) {
        return new RouteDraft(title, origin, opening, List.of(origin + "已经成立"), List.of("来历尚未查清"),
                List.of(new com.wenxian.game.story.agent.ai.CharacterCreationAi.StartingEntityDraft(
                        "ITEM", "残缺信物", "与来历有关")),
                new OpeningSceneDraft(title, opening + "，洞外传来脚步声。", List.of(
                        new OpeningChoiceDraft("与来人交谈", "询问此地情形，再决定下一步。"))));
    }
}

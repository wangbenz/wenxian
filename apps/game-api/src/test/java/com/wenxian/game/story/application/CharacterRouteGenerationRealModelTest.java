package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.application.ModelInvocationService;
import com.wenxian.game.model.application.ModelRoutePolicy;
import com.wenxian.game.model.application.ModelTaskRouter;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import com.wenxian.game.model.domain.ModelRouteCandidate;
import com.wenxian.game.model.infrastructure.AiServiceRegistry;
import com.wenxian.game.model.infrastructure.ChatModelRegistry;
import com.wenxian.game.model.infrastructure.OpenAiCompatibleChatModelFactory;
import com.wenxian.game.model.infrastructure.PromptResourceLoader;
import com.wenxian.game.story.agent.ai.LangChain4jCharacterRouteGenerator;
import com.wenxian.game.story.agent.ai.StoryAiServiceRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用真实模型验证角色路线类型化 AI Service，不访问 RDS 或玩家命卷。 */
class CharacterRouteGenerationRealModelTest {

    @Test
    void generatesThreeSpiritRoutesThroughNeutralModelStack() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "set DEEPSEEK_API_KEY to run character route smoke");
        ModelDefinition definition = new ModelDefinition("DEEPSEEK", "TEST_MODEL",
                value(System.getenv("DEEPSEEK_BASE_URL"), "https://api.deepseek.com"),
                ModelAdapterType.OPENAI_COMPATIBLE,
                value(System.getenv("DEEPSEEK_MODEL"), "deepseek-v4-flash"),
                Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL,
                        ModelCapability.CACHE_USAGE), 128_000, 8_000);
        ModelRouteCandidate candidate = new ModelRouteCandidate(definition, ModelCredential.platform(key),
                ModelInvocationOptions.defaults());
        ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(
                CredentialSource.PLATFORM, List.of(candidate));
        ChatModelRegistry models = new ChatModelRegistry(new OpenAiCompatibleChatModelFactory(), 4);
        ModelInvocationService invocations = new ModelInvocationService(new ModelTaskRouter(policy), models);
        StoryAiServiceRegistry services = new StoryAiServiceRegistry(
                new AiServiceRegistry(new PromptResourceLoader(), 8));
        CharacterRouteGenerationService service = new CharacterRouteGenerationService(
                new LangChain4jCharacterRouteGenerator(invocations, services), new ObjectMapper());

        long started = System.nanoTime();
        var routes = service.generate("real-character-route", new CharacterRouteGenerationService.Input(
                "XUANXU", 1, "玄墟界", "仙路真假难辨，各方仍为长生争夺资源。",
                List.of("事实不可静默改写"), "古典宗门修仙", "region:xuanxu:qingqi", "东陆青岐州",
                "砚青", "race:xuanxu:spirit", "灵族", "FEMALE",
                "一方古砚在灵脉中生出灵智", "自由探索，成长偏爽"));

        assertThat(routes).hasSize(3).extracting(CharacterRouteGenerationService.CharacterRouteCandidate::id)
                .containsExactly("route-1", "route-2", "route-3");
        assertThat(routes).allSatisfy(route -> {
            assertThat(route.title()).isNotBlank();
            assertThat(route.origin()).containsAnyOf("砚", "灵");
            assertThat(route.opening()).contains("你");
            assertThat(route.establishedFacts()).isNotEmpty();
        });
        System.out.printf("CHARACTER_ROUTE_AI_SERVICE latencyMs=%d cachedModels=%d titles=%s%n",
                (System.nanoTime() - started) / 1_000_000, models.size(),
                routes.stream().map(CharacterRouteGenerationService.CharacterRouteCandidate::title).toList());
    }

    private String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }
}

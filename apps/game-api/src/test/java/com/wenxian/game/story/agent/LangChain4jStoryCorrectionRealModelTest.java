package com.wenxian.game.story.agent;

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
import com.wenxian.game.story.agent.ai.StoryAiServiceRegistry;
import com.wenxian.game.story.domain.StoryCorrectionProposal;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用真实模型验证命卷修正类型化提案；不访问 RDS、不写玩家数据。 */
class LangChain4jStoryCorrectionRealModelTest {
    @Test
    void proposesExistingItemQuantityCorrectionThroughTypedAiService() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "set DEEPSEEK_API_KEY to run correction smoke");
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
        LangChain4jStoryCorrectionAgent agent = new LangChain4jStoryCorrectionAgent(
                invocations, services, new ObjectMapper().findAndRegisterModules());

        long started = System.nanoTime();
        StoryCorrectionProposal proposal = agent.propose("real-correction", "real-correction-1", context(),
                "我确认乾坤袋里实际只有三枚青灵丹，请把原来记录的五枚改成三枚。").proposal();

        assertThat(proposal.kind()).isEqualTo(StoryCorrectionProposal.CorrectionKind.FACT);
        assertThat(proposal.entities()).singleElement().satisfies(entity -> {
            assertThat(entity.entityId()).isEqualTo("item-qinglingdan");
            assertThat(entity.quantity()).isNotNull();
            assertThat(entity.quantity().known()).isTrue();
            assertThat(entity.quantity().value()).isEqualTo(3);
        });
        System.out.printf("STORY_CORRECTION_AI_SERVICE latencyMs=%d cachedModels=%d kind=%s%n",
                (System.nanoTime() - started) / 1_000_000, models.size(), proposal.kind());
    }

    private StoryPromptContext context() {
        StoryPromptContext.EntityIndexEntry item = new StoryPromptContext.EntityIndexEntry(
                "item-qinglingdan", "ITEM", "青灵丹", "持有中", true,
                new com.wenxian.game.story.domain.StoryPatch.QuantityValue(true, 5), true,
                com.wenxian.game.story.domain.StoryPatch.AbilityState.UNKNOWN,
                com.wenxian.game.story.domain.StoryPatch.LifecycleState.UNKNOWN);
        return new StoryPromptContext(
                new StoryPromptContext.SystemContext(List.of("已确认事实不可静默改写"), List.of()),
                new StoryPromptContext.ObjectiveContext(com.wenxian.game.story.domain.StoryWorldTime.opening(),
                        new CoreState("青岐城客栈", "平静", "引气", "问道"),
                        List.of(), List.of(item), List.of()),
                new StoryPromptContext.CognitionContext(List.of()),
                new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("此前整理了乾坤袋。", null),
                new StoryPromptContext.ActionContext(""));
    }

    private String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }
}

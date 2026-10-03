package com.wenxian.game.model.application;

import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import com.wenxian.game.model.domain.ModelRoute;
import com.wenxian.game.model.domain.ModelRouteCandidate;
import com.wenxian.game.model.domain.ModelRouteRequest;
import com.wenxian.game.model.domain.ModelTask;
import com.wenxian.game.model.infrastructure.ChatModelRegistry;
import com.wenxian.game.model.infrastructure.ModelResponseCapture;
import com.wenxian.game.model.infrastructure.OpenAiTokenUsageResolver;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.output.OutputParsingException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;

class ModelInvocationServiceTest {
    @Test
    void reusesRegisteredModelAndReturnsTypedContent() {
        ModelRouteCandidate candidate = candidate("flash", "secret-one");
        ModelInvocationService service = service(List.of(candidate));

        var first = service.invoke(request("one"), ignored -> new FakeAi(false), FakeAi::call);
        var second = service.invoke(request("two"), ignored -> new FakeAi(false), FakeAi::call);

        assertThat(first.content()).isEqualTo("ok");
        assertThat(second.content()).isEqualTo("ok");
        assertThat(first.fallbackUsed()).isFalse();
    }

    @Test
    void fallsBackOnceOnlyForRetriableFailure() {
        ModelRouteCandidate primary = candidate("flash", "secret-two");
        ModelRouteCandidate fallback = candidate("pro", "secret-three");
        ModelInvocationService service = service(List.of(primary, fallback));
        AtomicInteger calls = new AtomicInteger();

        var result = service.invoke(request("fallback"), ignored -> new FakeAi(calls.getAndIncrement() == 0),
                FakeAi::call);

        assertThat(result.content()).isEqualTo("ok");
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.attempts()).extracting(ModelInvocationService.Attempt::status)
                .containsExactly("FAILED", "SUCCESS");
    }

    @Test
    void observesEachAttemptAndPreservesCacheUsageWithoutNarrativeContent() {
        ModelRouteCandidate candidate = candidate("flash", "secret-observe");
        List<ModelInvocationObserver.Observation> observations = new java.util.ArrayList<>();
        ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(
                CredentialSource.PLATFORM, List.of(candidate));
        ChatModelRegistry registry = new ChatModelRegistry(
                (definition, credential, options) -> mock(ChatModel.class), 8);
        ModelInvocationService service = new ModelInvocationService(new ModelTaskRouter(policy), registry,
                List.of(observations::add), List.of(new OpenAiTokenUsageResolver()));

        service.invoke(request("observed"), ignored -> new FakeAi(false), ignored -> Result.<String>builder()
                .content("private narrative")
                .tokenUsage(OpenAiTokenUsage.builder()
                        .inputTokenCount(120)
                        .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder()
                                .cachedTokens(80).build())
                        .outputTokenCount(30)
                        .totalTokenCount(150)
                        .build())
                .build());

        assertThat(observations).singleElement().satisfies(observation -> {
            assertThat(observation.taskCode()).isEqualTo("CHARACTER_CREATION");
            assertThat(observation.requestId()).isEqualTo("request-observed");
            assertThat(observation.attemptNumber()).isEqualTo(1);
            assertThat(observation.cacheHitTokens()).isEqualTo(80);
            assertThat(observation.cacheMissTokens()).isEqualTo(40);
            assertThat(observation.toString()).doesNotContain("private narrative", "secret-observe");
        });
    }

    @Test
    void observesDeepSeekCompatibleCacheFieldsFromFinalResponse() {
        ModelRouteCandidate candidate = candidate("flash", "secret-deepseek-cache");
        List<ModelInvocationObserver.Observation> observations = new java.util.ArrayList<>();
        ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(
                CredentialSource.PLATFORM, List.of(candidate));
        ChatModelRegistry registry = new ChatModelRegistry(
                (definition, credential, options) -> mock(ChatModel.class), 8);
        ModelInvocationService service = new ModelInvocationService(new ModelTaskRouter(policy), registry,
                List.of(observations::add), List.of(new OpenAiTokenUsageResolver()));
        SuccessfulHttpResponse raw = SuccessfulHttpResponse.builder().statusCode(200).headers(java.util.Map.of())
                .body("{\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":20,"
                        + "\"prompt_cache_hit_tokens\":90,\"prompt_cache_miss_tokens\":30}}")
                .build();
        OpenAiChatResponseMetadata metadata = OpenAiChatResponseMetadata.builder()
                .rawHttpResponse(raw).tokenUsage(new dev.langchain4j.model.output.TokenUsage(120, 20)).build();
        ChatResponse finalResponse = ChatResponse.builder().aiMessage(AiMessage.from("{}"))
                .metadata(metadata).build();

        service.invoke(request("deepseek-cache"), ignored -> new FakeAi(false), ignored -> Result.<String>builder()
                .content("private narrative")
                .tokenUsage(new dev.langchain4j.model.output.TokenUsage(120, 20))
                .finalResponse(finalResponse)
                .build());

        assertThat(observations).singleElement().satisfies(observation -> {
            assertThat(observation.cacheHitTokens()).isEqualTo(90);
            assertThat(observation.cacheMissTokens()).isEqualTo(30);
        });
    }

    @Test
    void recordsKnownUsageForDecodingFailureWithoutFallbackOrLeakingTheResponse() {
        List<ModelInvocationObserver.Observation> observations = new java.util.ArrayList<>();
        ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(CredentialSource.PLATFORM,
                List.of(candidate("flash", "private-credential"), candidate("fallback", "private-fallback")));
        ChatModelRegistry registry = new ChatModelRegistry(
                (definition, credential, options) -> mock(ChatModel.class), 8);
        ModelInvocationService service = new ModelInvocationService(new ModelTaskRouter(policy), registry,
                List.of(observations::add), List.of(new OpenAiTokenUsageResolver()));
        String rawText = "private-invalid-director-response";
        var raw = SuccessfulHttpResponse.builder().statusCode(200).headers(java.util.Map.of())
                .body("{\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":20,"
                        + "\"prompt_cache_hit_tokens\":90,\"prompt_cache_miss_tokens\":30}}")
                .build();
        var response = ChatResponse.builder().aiMessage(AiMessage.from(rawText))
                .metadata(OpenAiChatResponseMetadata.builder().rawHttpResponse(raw)
                        .tokenUsage(new dev.langchain4j.model.output.TokenUsage(120, 20)).build()).build();
        AtomicInteger calls = new AtomicInteger();
        ModelRouteRequest request = new ModelRouteRequest(ModelTask.TURN_DIRECTOR,
                "request-decoding", "user-1", false);

        var failure = catchThrowableOfType(() -> service.invoke(request, ignored -> new FakeAi(false),
                ignored -> ModelResponseCapture.<Result<String>>invoke(() -> {
                    calls.incrementAndGet();
                    ModelResponseCapture.record(response);
                    throw new OutputParsingException(rawText, new IllegalArgumentException(rawText));
                })), ModelInvocationService.ModelInvocationException.class);

        assertThat(calls).hasValue(1);
        assertThat(failure.getCause()).isInstanceOfSatisfying(ModelResponseCapture.DecodingFailure.class,
                decoding -> {
                    assertThat(decoding.response()).isSameAs(response);
                    assertThat(decoding.getCause()).isNull();
                });
        assertThat(failure.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo("FAILED");
            assertThat(attempt.fallback()).isFalse();
            assertThat(attempt.inputTokens()).isEqualTo(120);
            assertThat(attempt.outputTokens()).isEqualTo(20);
            assertThat(attempt.cacheHitTokens()).isEqualTo(90);
            assertThat(attempt.cacheMissTokens()).isEqualTo(30);
            assertThat(attempt.errorCategory()).isEqualTo("DecodingFailure");
            assertThat(attempt.toString()).doesNotContain(rawText, "private-credential");
        });
        assertThat(observations).singleElement().satisfies(observation -> {
            assertThat(observation.status()).isEqualTo("FAILED");
            assertThat(observation.inputTokens()).isEqualTo(120);
            assertThat(observation.outputTokens()).isEqualTo(20);
            assertThat(observation.cacheHitTokens()).isEqualTo(90);
            assertThat(observation.cacheMissTokens()).isEqualTo(30);
            assertThat(observation.toString()).doesNotContain(rawText, "private-credential");
        });
    }

    @Test
    void recordsUnknownUsageAsZeroWhenDecodingFailedWithoutAResponseEvent() {
        ModelInvocationService service = service(List.of(candidate("flash", "private-credential")));

        var failure = catchThrowableOfType(() -> service.invoke(request("no-response"),
                ignored -> new FakeAi(false), ignored -> ModelResponseCapture.<Result<String>>invoke(() -> {
                    throw new OutputParsingException("invalid payload", null);
                })), ModelInvocationService.ModelInvocationException.class);

        assertThat(failure.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo("FAILED");
            assertThat(attempt.inputTokens()).isZero();
            assertThat(attempt.outputTokens()).isZero();
            assertThat(attempt.cacheHitTokens()).isZero();
            assertThat(attempt.cacheMissTokens()).isZero();
        });
    }

    @Test
    void cacheUsageResolverFailureDoesNotReplaceDecodingFailureOrKnownTokenCounts() {
        ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(CredentialSource.PLATFORM,
                List.of(candidate("flash", "private-credential")));
        ChatModelRegistry registry = new ChatModelRegistry(
                (definition, credential, options) -> mock(ChatModel.class), 8);
        ModelInvocationService service = new ModelInvocationService(new ModelTaskRouter(policy), registry,
                List.of(), List.of(result -> { throw new IllegalStateException("cache metadata unavailable"); }));
        var response = ChatResponse.builder().aiMessage(AiMessage.from("invalid"))
                .tokenUsage(new dev.langchain4j.model.output.TokenUsage(50, 10)).build();

        var failure = catchThrowableOfType(() -> service.invoke(request("cache-failure"),
                ignored -> new FakeAi(false), ignored -> ModelResponseCapture.<Result<String>>invoke(() -> {
                    ModelResponseCapture.record(response);
                    throw new OutputParsingException("invalid", null);
                })), ModelInvocationService.ModelInvocationException.class);

        assertThat(failure.getCause()).isInstanceOf(ModelResponseCapture.DecodingFailure.class);
        assertThat(failure.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo("FAILED");
            assertThat(attempt.inputTokens()).isEqualTo(50);
            assertThat(attempt.outputTokens()).isEqualTo(10);
        });
    }

    @Test
    void observerFailureNeverChangesSuccessfulModelTask() {
        ModelRouteCandidate candidate = candidate("flash", "secret-observer-failure");
        ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(
                CredentialSource.PLATFORM, List.of(candidate));
        ChatModelRegistry registry = new ChatModelRegistry(
                (definition, credential, options) -> mock(ChatModel.class), 8);
        ModelInvocationService service = new ModelInvocationService(new ModelTaskRouter(policy), registry,
                List.of(observation -> { throw new IllegalStateException("usage unavailable"); }), List.of());

        assertThat(service.invoke(request("observer-failure"), ignored -> new FakeAi(false), FakeAi::call)
                .content()).isEqualTo("ok");
    }

    @Test
    void authorizationFailureStopsBeforeCreatingOrCallingModel() {
        ModelRouteCandidate candidate = candidate("flash", "secret-authorize");
        ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(
                CredentialSource.PLATFORM, List.of(candidate));
        AtomicInteger modelCreations = new AtomicInteger();
        ChatModelRegistry registry = new ChatModelRegistry((definition, credential, options) -> {
            modelCreations.incrementAndGet();
            return mock(ChatModel.class);
        }, 8);
        ModelInvocationService service = new ModelInvocationService(new ModelTaskRouter(policy), registry,
                List.of(), List.of(), List.of((request, route) -> {
                    throw new IllegalStateException("quota unavailable");
                }));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.invoke(request("not-authorized"),
                        ignored -> new FakeAi(false), FakeAi::call))
                .isInstanceOf(IllegalStateException.class).hasMessage("quota unavailable");
        assertThat(modelCreations).hasValue(0);
    }

    @Test
    void invokesTheAlreadyResolvedRouteWithoutResolvingAgain() {
        ModelRouteCandidate candidate = candidate("resolved-flash", "secret-resolved");
        ModelRouteRequest request = request("resolved");
        ModelRoute resolved = new ModelRoute(request.task(), request.requestId(), candidate, null);
        ModelRoutePolicy forbiddenSecondResolution = ignored -> {
            throw new AssertionError("额度预占后不得再次解析模型路由");
        };
        ChatModelRegistry registry = new ChatModelRegistry(
                (definition, credential, options) -> mock(ChatModel.class), 8);
        ModelInvocationService service = new ModelInvocationService(
                new ModelTaskRouter(forbiddenSecondResolution), registry);

        var result = service.invokeResolved(request, resolved, ignored -> new FakeAi(false), FakeAi::call);

        assertThat(result.content()).isEqualTo("ok");
        assertThat(result.model().modelCode()).isEqualTo("resolved-flash");
        assertThat(result.credentialSource()).isEqualTo(CredentialSource.PLATFORM);
    }

    @Test
    void rejectsResolvedRouteFromAnotherTaskOrRequest() {
        ModelRouteCandidate candidate = candidate("resolved", "secret-mismatch");
        ModelInvocationService service = service(List.of(candidate));
        ModelRouteRequest request = request("expected");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.invokeResolved(request,
                        new ModelRoute(ModelTask.STORY_CORRECTION, request.requestId(), candidate, null),
                        ignored -> new FakeAi(false), FakeAi::call))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("已解析模型路由与任务不一致");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.invokeResolved(request,
                        new ModelRoute(request.task(), "request-other", candidate, null),
                        ignored -> new FakeAi(false), FakeAi::call))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("已解析模型路由与请求编号不一致");
    }

    private ModelInvocationService service(List<ModelRouteCandidate> candidates) {
        ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(
                CredentialSource.PLATFORM, candidates);
        ChatModelRegistry registry = new ChatModelRegistry(
                (definition, credential, options) -> mock(ChatModel.class), 8);
        return new ModelInvocationService(new ModelTaskRouter(policy), registry);
    }

    private ModelRouteRequest request(String suffix) {
        return new ModelRouteRequest(ModelTask.CHARACTER_CREATION, "request-" + suffix, "user-1", false);
    }

    private ModelRouteCandidate candidate(String code, String secret) {
        ModelDefinition model = new ModelDefinition("TEST", code, "https://example.invalid",
                ModelAdapterType.OPENAI_COMPATIBLE, code,
                Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL),
                64_000, 8_000);
        return new ModelRouteCandidate(model, ModelCredential.platform(secret), ModelInvocationOptions.defaults());
    }

    private record FakeAi(boolean fail) {
        private Result<String> call() {
            if (fail) throw new RateLimitException("temporary rate limit");
            return Result.<String>builder().content("ok").build();
        }
    }
}

package com.wenxian.game.model.application;

import com.wenxian.game.model.domain.ModelRoute;
import com.wenxian.game.model.domain.ModelRouteCandidate;
import com.wenxian.game.model.domain.ModelRouteRequest;
import com.wenxian.game.model.infrastructure.ChatModelRegistry;
import com.wenxian.game.model.infrastructure.ModelResponseCapture.DecodingFailure;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 统一执行一次类型化模型任务，并在允许时最多降级一次。
 *
 * <p>本服务不扣额度、不写故事事实。调用方必须在业务事务外完成模型请求，
 * 最终仍由原有用例负责幂等提交。</p>
 */
@Component
public class ModelInvocationService {
    private final ModelTaskRouter router;
    private final ChatModelRegistry models;
    private final List<ModelInvocationObserver> observers;
    private final List<ModelTokenUsageResolver> tokenUsageResolvers;
    private final List<ModelInvocationAuthorizer> authorizers;

    public ModelInvocationService(ModelTaskRouter router, ChatModelRegistry models) {
        this(router, models, List.of(), List.of(), List.of());
    }

    public ModelInvocationService(ModelTaskRouter router, ChatModelRegistry models,
                                  List<ModelInvocationObserver> observers,
                                  List<ModelTokenUsageResolver> tokenUsageResolvers) {
        this(router, models, observers, tokenUsageResolvers, List.of());
    }

    @Autowired
    public ModelInvocationService(ModelTaskRouter router, ChatModelRegistry models,
                                  List<ModelInvocationObserver> observers,
                                  List<ModelTokenUsageResolver> tokenUsageResolvers,
                                  List<ModelInvocationAuthorizer> authorizers) {
        this.router = router;
        this.models = models;
        this.observers = List.copyOf(observers);
        this.tokenUsageResolvers = List.copyOf(tokenUsageResolvers);
        this.authorizers = List.copyOf(authorizers);
    }

    public <S, T> Invocation<T> invoke(ModelRouteRequest request,
                                       Function<ChatModelRegistry.RegisteredChatModel, S> serviceResolver,
                                       Function<S, Result<T>> call) {
        ModelRoute route = router.route(request);
        return invokeResolved(request, route, serviceResolver, call);
    }

    /**
     * 使用业务用例已经解析并用于额度判断的同一份路由执行模型任务。
     *
     * <p>该入口用于长回合迁移，避免调用层在额度预占后再次选择模型。传入路由必须与任务和请求编号完全一致；
     * 路由仍会经过授权器、观测、模型复用和至多一次降级，但不会再次访问路由策略。</p>
     */
    public <S, T> Invocation<T> invokeResolved(ModelRouteRequest request, ModelRoute route,
                                               Function<ChatModelRegistry.RegisteredChatModel, S> serviceResolver,
                                               Function<S, Result<T>> call) {
        requireMatchingRoute(request, route);
        authorizers.forEach(authorizer -> authorizer.authorize(request, route));
        List<Attempt> attempts = new ArrayList<>();
        try {
            return attempt(request, route.primary(), false, serviceResolver, call, attempts);
        } catch (RuntimeException primaryFailure) {
            ModelRouteCandidate fallback = route.fallbackCandidate().orElse(null);
            if (fallback == null || !fallbackEligible(primaryFailure)) {
                throw new ModelInvocationException("MODEL_INVOCATION_FAILED", List.copyOf(attempts), primaryFailure);
            }
            try {
                return attempt(request, fallback, true, serviceResolver, call, attempts);
            } catch (RuntimeException fallbackFailure) {
                fallbackFailure.addSuppressed(primaryFailure);
                throw new ModelInvocationException("MODEL_FALLBACK_FAILED", List.copyOf(attempts), fallbackFailure);
            }
        }
    }

    private void requireMatchingRoute(ModelRouteRequest request, ModelRoute route) {
        if (request == null) throw new IllegalArgumentException("模型请求不能为空");
        if (route == null) throw new IllegalArgumentException("已解析模型路由不能为空");
        if (route.task() != request.task()) {
            throw new IllegalArgumentException("已解析模型路由与任务不一致");
        }
        if (!route.requestId().equals(request.requestId())) {
            throw new IllegalArgumentException("已解析模型路由与请求编号不一致");
        }
    }

    private <S, T> Invocation<T> attempt(ModelRouteRequest request, ModelRouteCandidate candidate, boolean fallback,
                                          Function<ChatModelRegistry.RegisteredChatModel, S> serviceResolver,
                                          Function<S, Result<T>> call, List<Attempt> attempts) {
        long started = System.nanoTime();
        int attemptNumber = attempts.size() + 1;
        try {
            ChatModelRegistry.RegisteredChatModel model = models.getOrCreate(candidate.model(),
                    candidate.credential(), candidate.options());
            Result<T> result = call.apply(serviceResolver.apply(model));
            if (result == null || result.content() == null) {
                throw new IllegalStateException("模型没有返回可用结构");
            }
            long latency = elapsed(started);
            TokenMetrics tokenMetrics = tokenMetrics(result);
            attempts.add(new Attempt(candidate.model().providerCode(), candidate.model().modelCode(),
                    candidate.credential().source().name(), fallback, "SUCCESS", latency,
                    tokenMetrics.inputTokens(), tokenMetrics.outputTokens(), tokenMetrics.cacheHitTokens(),
                    tokenMetrics.cacheMissTokens(), ""));
            observe(request, attemptNumber, candidate, fallback, "SUCCESS", latency, tokenMetrics, "");
            return new Invocation<>(result.content(), result, candidate.model(),
                    candidate.credential().source(), fallback, List.copyOf(attempts));
        } catch (RuntimeException error) {
            long latency = elapsed(started);
            TokenMetrics tokenMetrics = failedTokenMetrics(error);
            attempts.add(new Attempt(candidate.model().providerCode(), candidate.model().modelCode(),
                    candidate.credential().source().name(), fallback, "FAILED", latency,
                    tokenMetrics.inputTokens(), tokenMetrics.outputTokens(), tokenMetrics.cacheHitTokens(),
                    tokenMetrics.cacheMissTokens(), error.getClass().getSimpleName()));
            observe(request, attemptNumber, candidate, fallback, "FAILED", latency,
                    tokenMetrics, error.getClass().getSimpleName());
            throw error;
        }
    }

    /** 解码失败不代表供应商没有产生用量；只读取本次已返回的明细，不改变失败或重试语义。 */
    private TokenMetrics failedTokenMetrics(RuntimeException error) {
        if (!(error instanceof DecodingFailure failure) || failure.response() == null
                || failure.response().tokenUsage() == null) return TokenMetrics.EMPTY;
        TokenUsage usage = failure.response().tokenUsage();
        try {
            // Result 仅作为现有用量解析器的输入，不伪造类型化内容或成功调用。
            return tokenMetrics(Result.builder().tokenUsage(usage).finalResponse(failure.response()).build());
        } catch (RuntimeException ignored) {
            // 缓存用量旁路异常不能覆盖原解码失败，仍保留明确的输入与输出数量。
            return new TokenMetrics(usage.inputTokenCount() == null ? 0 : usage.inputTokenCount(),
                    usage.outputTokenCount() == null ? 0 : usage.outputTokenCount(), 0, 0);
        }
    }

    private TokenMetrics tokenMetrics(Result<?> result) {
        TokenUsage tokenUsage = result.tokenUsage();
        if (tokenUsage == null) return TokenMetrics.EMPTY;
        int input = tokenUsage.inputTokenCount() == null ? 0 : tokenUsage.inputTokenCount();
        int output = tokenUsage.outputTokenCount() == null ? 0 : tokenUsage.outputTokenCount();
        for (ModelTokenUsageResolver resolver : tokenUsageResolvers) {
            var cacheUsage = resolver.resolve(result);
            if (cacheUsage.isPresent()) {
                return new TokenMetrics(input, output, cacheUsage.get().hitTokens(), cacheUsage.get().missTokens());
            }
        }
        return new TokenMetrics(input, output, 0, 0);
    }

    private void observe(ModelRouteRequest request, int attemptNumber, ModelRouteCandidate candidate,
                         boolean fallback, String status, long latency, TokenMetrics tokens,
                         String errorCategory) {
        var observation = new ModelInvocationObserver.Observation(request.task().name(), request.requestId(),
                request.userId(), attemptNumber, candidate.model().providerCode(), candidate.model().modelCode(),
                candidate.credential().source().name(), fallback, status, tokens.inputTokens(),
                tokens.outputTokens(), tokens.cacheHitTokens(), tokens.cacheMissTokens(), latency, errorCategory);
        for (ModelInvocationObserver observer : observers) {
            try {
                observer.observe(observation);
            } catch (RuntimeException ignored) {
                // 观测旁路不可改变已经成功或失败的模型任务语义。
            }
        }
    }

    private boolean fallbackEligible(RuntimeException error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RateLimitException || current instanceof RetriableException) return true;
            current = current.getCause();
        }
        return false;
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    public record Invocation<T>(
            T content,
            Result<T> rawResult,
            com.wenxian.game.model.domain.ModelDefinition model,
            com.wenxian.game.model.domain.CredentialSource credentialSource,
            boolean fallbackUsed,
            List<Attempt> attempts
    ) {}

    /** 不含密钥、Prompt和响应正文的单次尝试摘要。 */
    public record Attempt(String providerCode, String modelCode, String credentialSource,
                          boolean fallback, String status, long latencyMs,
                          int inputTokens, int outputTokens, int cacheHitTokens, int cacheMissTokens,
                          String errorCategory) {}

    private record TokenMetrics(int inputTokens, int outputTokens, int cacheHitTokens, int cacheMissTokens) {
        private static final TokenMetrics EMPTY = new TokenMetrics(0, 0, 0, 0);
    }

    public static final class ModelInvocationException extends IllegalStateException {
        private final String code;
        private final List<Attempt> attempts;

        public ModelInvocationException(String code, List<Attempt> attempts, RuntimeException cause) {
            super("模型任务没有完成", cause);
            this.code = code;
            this.attempts = attempts;
        }

        public String code() {
            return code;
        }

        public List<Attempt> attempts() {
            return attempts;
        }
    }
}

package com.wenxian.game.v014.ai;

import com.wenxian.game.story.agent.LangChain4jStoryModelGateway;
import com.wenxian.game.story.agent.StoryModelGateway;
import org.springframework.stereotype.Component;

/**
 * V0.14 模型调用兼容适配器。
 *
 * <p>实际供应商调用已经下沉到中性 {@link StoryModelGateway}；该类只保留旧 Agent 的请求和响应类型，
 * 避免在 V0.15 建设期间改变仍在运行的旧接口行为。</p>
 */
@Component
public class LangChain4jModelGateway implements V014ModelGateway {
    private final StoryModelGateway delegate;

    public LangChain4jModelGateway() {
        this(new LangChain4jStoryModelGateway());
    }

    LangChain4jModelGateway(StoryModelGateway delegate) {
        this.delegate = delegate;
    }

    @Override
    public Result generate(Request request) {
        StoryModelGateway.Result result = delegate.generate(new StoryModelGateway.Request(
                request.baseUrl(), request.apiKey(), request.provider(), request.adapterType(), request.model(),
                request.systemPrompt(), request.userPrompt(), request.temperature()));
        return new Result(result.text(), result.responseId(), result.model(), result.inputTokens(),
                result.outputTokens(), result.latencyMs());
    }
}

package com.wenxian.game.story.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.StoryCorrectionAgent;
import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.application.CorrectionProposalTokenCodec;
import com.wenxian.game.story.application.StoryCorrectionWorkflow;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import com.wenxian.game.story.reducer.StoryStateReducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * 可信叙事组件的装配入口。
 *
 * <p>这里只装配无状态的映射器、短时修正令牌和两阶段工作流，不开启任何迁移或运行时开关。
 * 修正令牌密钥只从服务端配置读取，不能下发到 H5 或写入数据库。</p>
 */
@Configuration
public class StoryTrustConfiguration {
    @Bean
    public PromptContextAssembler promptContextAssembler() {
        return new PromptContextAssembler();
    }

    @Bean
    public StoryProjectionPersistenceMapper storyProjectionPersistenceMapper(ObjectMapper mapper) {
        return new StoryProjectionPersistenceMapper(mapper);
    }

    @Bean
    public StoryStateReducer storyStateReducer() {
        return new StoryStateReducer();
    }

    @Bean
    public CorrectionProposalTokenCodec correctionProposalTokenCodec(
            ObjectMapper mapper,
            @Value("${game.story-correction-token-secret}") String secret,
            @Value("${game.story-correction-token-ttl-minutes:10}") long lifetimeMinutes) {
        return new CorrectionProposalTokenCodec(mapper, secret, Duration.ofMinutes(lifetimeMinutes),
                Clock.systemUTC());
    }

    @Bean
    public StoryCorrectionWorkflow storyCorrectionWorkflow(StoryCorrectionAgent agent,
                                                             CorrectionProposalTokenCodec tokens) {
        return new StoryCorrectionWorkflow(agent, tokens);
    }
}

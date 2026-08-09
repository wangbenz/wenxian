package com.wenxian.game;

import com.wenxian.game.story.agent.AiUsageStoryModelInvocationRecorder;
import com.wenxian.game.story.agent.ModelBackedStoryTurnAgent;
import com.wenxian.game.story.application.StoryTurnCommitService;
import com.wenxian.game.story.application.StoryTurnPreparationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 使用隔离 H2 数据库启动完整应用容器，防止可信回合组件只在手工构造的单元测试中可用。
 *
 * <p>该测试不调用真实模型、不写 RDS，也不启用可信回合开关；它只验证正式 Spring 装配、数据库脚本和
 * 关键服务依赖能够共同启动。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:wenxian-context;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.schema-locations=classpath:schema-h2.sql",
        "spring.sql.init.mode=always",
        "wenxian.story.trusted-turns-enabled=false",
        "game.auth.secure-cookie=false"
})
class WenxianGameApplicationContextTest {
    @Autowired private ModelBackedStoryTurnAgent agent;
    @Autowired private AiUsageStoryModelInvocationRecorder usageRecorder;
    @Autowired private StoryTurnPreparationService preparation;
    @Autowired private StoryTurnCommitService commit;

    @Test
    void wiresTrustedStoryComponentsWithoutEnablingProductionPath() {
        assertNotNull(agent);
        assertNotNull(usageRecorder);
        assertNotNull(preparation);
        assertNotNull(commit);
    }
}

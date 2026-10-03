package com.wenxian.game.mail;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AliyunDirectMailConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AliyunDirectMailConfiguration.class);

    @Test
    void staysDisabledWithoutCredentialsByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TransactionalMailGateway.class);
        });
    }

    @Test
    void refusesToStartWhenEnabledWithoutCredentials() {
        contextRunner
                .withPropertyValues(
                        "game.mail.enabled=true",
                        "game.mail.aliyun.account-name=no-reply@notice.wenxianlu.cn",
                        "game.mail.aliyun.from-alias=问仙录"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessage("game.mail.aliyun.access-key-id must be configured when mail is enabled");
                });
    }

    @Test
    void createsGatewayOnlyAfterAllRequiredSettingsExist() {
        contextRunner
                .withPropertyValues(
                        "game.mail.enabled=true",
                        "game.mail.aliyun.access-key-id=test-key-id",
                        "game.mail.aliyun.access-key-secret=test-key-secret",
                        "game.mail.aliyun.account-name=no-reply@notice.wenxianlu.cn",
                        "game.mail.aliyun.from-alias=问仙录"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TransactionalMailGateway.class);
                });
    }
}

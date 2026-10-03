package com.wenxian.game.mail;

import com.aliyun.dm20151123.Client;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云邮件推送客户端装配。
 *
 * <p>只有显式打开 {@code game.mail.enabled} 才创建客户端。打开后凭证或发信地址缺失会阻止应用启动，
 * 避免验证码流程在“看似可用、实际不能发信”的状态下接收请求。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "game.mail", name = "enabled", havingValue = "true")
class AliyunDirectMailConfiguration {
    @Bean
    TransactionalMailGateway transactionalMailGateway(
            @Value("${game.mail.aliyun.access-key-id:}") String accessKeyId,
            @Value("${game.mail.aliyun.access-key-secret:}") String accessKeySecret,
            @Value("${game.mail.aliyun.account-name:}") String accountName,
            @Value("${game.mail.aliyun.from-alias:}") String fromAlias,
            @Value("${game.mail.aliyun.endpoint:dm.aliyuncs.com}") String endpoint,
            @Value("${game.mail.aliyun.region-id:cn-hangzhou}") String regionId,
            @Value("${game.mail.aliyun.connect-timeout-millis:5000}") int connectTimeoutMillis,
            @Value("${game.mail.aliyun.read-timeout-millis:10000}") int readTimeoutMillis) throws Exception {
        Config config = new Config()
                .setAccessKeyId(requireSecret(accessKeyId, "game.mail.aliyun.access-key-id"))
                .setAccessKeySecret(requireSecret(accessKeySecret, "game.mail.aliyun.access-key-secret"))
                .setEndpoint(requireSetting(endpoint, "game.mail.aliyun.endpoint"))
                .setRegionId(requireSetting(regionId, "game.mail.aliyun.region-id"));
        Client client = new Client(config);
        AliyunSingleSendClient singleSendClient = request -> client.singleSendMailWithOptions(
                request,
                new RuntimeOptions()
                        .setAutoretry(false)
                        .setConnectTimeout(positiveTimeout(connectTimeoutMillis, "connect-timeout-millis"))
                        .setReadTimeout(positiveTimeout(readTimeoutMillis, "read-timeout-millis"))
        );
        return new AliyunDirectMailGateway(singleSendClient, accountName, fromAlias);
    }

    private static String requireSecret(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be configured when mail is enabled");
        }
        return value.strip();
    }

    private static String requireSetting(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must not be blank");
        }
        return value.strip();
    }

    private static int positiveTimeout(int value, String propertyName) {
        if (value <= 0) {
            throw new IllegalStateException("game.mail.aliyun." + propertyName + " must be positive");
        }
        return value;
    }
}

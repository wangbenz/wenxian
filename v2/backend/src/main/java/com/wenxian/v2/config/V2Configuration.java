package com.wenxian.v2.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
@EnableConfigurationProperties(V2Properties.class)
public class V2Configuration {
    @Bean(name = "v2TurnExecutor", destroyMethod = "close")
    ExecutorService v2TurnExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

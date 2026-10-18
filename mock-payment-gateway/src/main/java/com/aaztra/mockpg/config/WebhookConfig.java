package com.aaztra.mockpg.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.util.concurrent.Executor;

/**
 * Configuration for webhook dispatch infrastructure.
 * Provides the RestClient for sending webhooks and the async executor.
 */
@Configuration
public class WebhookConfig {

    @Bean
    @Qualifier("webhookRestClient")
    public RestClient webhookRestClient() {
        return RestClient.builder()
                .build();
    }

    @Bean("webhookExecutor")
    public Executor webhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("webhook-");
        executor.initialize();
        return executor;
    }
}

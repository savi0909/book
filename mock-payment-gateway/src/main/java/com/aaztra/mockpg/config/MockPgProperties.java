package com.aaztra.mockpg.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Type-safe configuration properties for the Mock Payment Gateway.
 * Binds to {@code mock-pg.*} in application.yml.
 */
@Component
@ConfigurationProperties(prefix = "mock-pg")
@Getter
@Setter
public class MockPgProperties {

    private String webhookUrl;
    private String webhookSecret;
    private String paymentSecret;
    private String checkoutBaseUrl;
    private List<String> allowedOrigins;
}

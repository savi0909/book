package com.aaztra.mockpg.service;

import java.util.Map;
import java.util.Optional;

/**
 * Service for managing mock payment processor runtime configuration.
 */
public interface MockConfigService {

    /**
     * Get webhook URL configuration.
     */
    Optional<String> getWebhookUrl();

    /**
     * Get failure rate percentage (0-100).
     */
    int getFailureRate();

    /**
     * Check if magic amounts are enabled.
     */
    boolean isMagicAmountsEnabled();

    /**
     * Check if auto-capture is enabled.
     */
    boolean isAutoCaptureEnabled();

    /**
     * Get all configuration as map.
     */
    Map<String, Object> getAllConfig();

    /**
     * Update configuration value.
     */
    void updateConfig(String key, Object value);

    /**
     * Reset configuration to defaults.
     */
    void resetToDefaults();
}

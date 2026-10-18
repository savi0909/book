package com.aaztra.mockpg.dto;

import java.util.Map;

/**
 * Response DTO for mock payment processor configuration.
 */
public record MockConfigResponse(
    Map<String, Object> config,
    String message
) {
    /**
     * Create a response with config data and default success message.
     */
    public static MockConfigResponse of(Map<String, Object> config) {
        return new MockConfigResponse(config, "Configuration retrieved successfully");
    }

    /**
     * Create a response with config data and custom message.
     */
    public static MockConfigResponse of(Map<String, Object> config, String message) {
        return new MockConfigResponse(config, message);
    }
}

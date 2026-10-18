package com.aaztra.mockpg.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for updating mock payment processor configuration.
 */
public record UpdateMockConfigRequest(
    @NotBlank(message = "Config key is required")
    String key,

    @NotNull(message = "Config value is required")
    Object value
) {}

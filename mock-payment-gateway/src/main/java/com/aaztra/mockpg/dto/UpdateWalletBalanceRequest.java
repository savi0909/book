package com.aaztra.mockpg.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for updating wallet balance.
 */
public record UpdateWalletBalanceRequest(
    @NotNull(message = "Balance is required")
    @Min(value = 0, message = "Balance must be non-negative")
    Long balance
) {}

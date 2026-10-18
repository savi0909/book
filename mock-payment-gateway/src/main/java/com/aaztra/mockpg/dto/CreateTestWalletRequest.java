package com.aaztra.mockpg.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for creating a test wallet.
 */
public record CreateTestWalletRequest(
    @NotBlank(message = "Wallet ID is required")
    String walletId,

    @Min(value = 0, message = "Balance must be non-negative")
    Long balance,

    String status
) {
    /**
     * Canonical constructor with defaults.
     */
    public CreateTestWalletRequest {
        if (balance == null) {
            balance = 100_000_00L; // Default ₹1,00,000
        }
        if (status == null || status.isBlank()) {
            status = "active";
        }
    }
}

package com.aaztra.mockpg.dto;

import com.aaztra.mockpg.entity.MockWallet;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Response DTO for mock wallet.
 */
public record MockWalletResponse(
    @JsonProperty("wallet_id") String walletId,
    String provider,
    Long balance,
    @JsonProperty("balance_formatted") String balanceFormatted,
    String status,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt
) {
    /**
     * Create a response from MockWallet entity.
     */
    public static MockWalletResponse from(MockWallet wallet) {
        String formatted = String.format("₹%,.2f", wallet.getBalance() / 100.0);
        return new MockWalletResponse(
            wallet.getWalletId(),
            wallet.getProvider(),
            wallet.getBalance(),
            formatted,
            wallet.getStatus(),
            wallet.getCreatedAt(),
            wallet.getUpdatedAt()
        );
    }
}

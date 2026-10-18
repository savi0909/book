package com.aaztra.mockpg.saga;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record SagaOperationRequest(
        @NotNull UUID orderId,
        @NotNull OperationType operationType,
        @Positive long amountMinor,
        @NotNull @Pattern(regexp = "INR") String currency,
        UUID captureOperationId) {
    public enum OperationType { CAPTURE, REFUND }
}

package com.aaztra.mockpg.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Request to create a refund for a captured payment.
 */
public record CreateMockRefundRequest(

    @NotNull(message = "Amount is required")
    @Min(value = 1, message = "Minimum refund amount is ₹1")
    Long amount,

    @Pattern(regexp = "^(normal|instant)$", message = "Speed must be: normal or instant")
    String speed,

    @Size(max = 40, message = "Receipt cannot exceed 40 characters")
    String receipt,

    Map<String, String> notes
) {
    public CreateMockRefundRequest {
        if (speed == null || speed.isBlank()) {
            speed = "normal";
        }
        if (notes == null) {
            notes = Map.of();
        }
    }
}

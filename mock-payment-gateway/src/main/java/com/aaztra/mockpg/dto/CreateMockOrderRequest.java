package com.aaztra.mockpg.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Request to create a mock order (Razorpay Orders API compatible).
 * Amount is in rupees (INR).
 */
public record CreateMockOrderRequest(

    @NotNull(message = "Amount is required")
    @Min(value = 1, message = "Minimum amount is ₹1")
    Long amount,

    @Size(min = 3, max = 3, message = "Currency must be 3 characters")
    String currency,

    @Size(max = 40, message = "Receipt cannot exceed 40 characters")
    String receipt,

    Map<String, String> notes
) {
    public CreateMockOrderRequest {
        // Default currency to INR if not specified
        if (currency == null || currency.isBlank()) {
            currency = "INR";
        }
        // Initialize notes to empty map if null
        if (notes == null) {
            notes = Map.of();
        }
    }
}

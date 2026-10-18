package com.aaztra.mockpg.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to capture an authorized payment.
 */
public record CapturePaymentRequest(

    @NotNull(message = "Amount is required")
    @Min(value = 100, message = "Minimum capture amount is 100 paise")
    Long amount,

    @Size(min = 3, max = 3, message = "Currency must be 3 characters")
    String currency
) {
    public CapturePaymentRequest {
        if (currency == null || currency.isBlank()) {
            currency = "INR";
        }
    }
}

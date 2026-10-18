package com.aaztra.mockpg.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO for manually triggering a webhook.
 */
public record TriggerWebhookRequest(
    @NotBlank(message = "Event type is required")
    @Pattern(regexp = "^(payment\\.(captured|authorized|failed)|refund\\.(processed|failed))$",
             message = "Invalid event type. Must be one of: payment.captured, payment.authorized, payment.failed, refund.processed, refund.failed")
    String eventType,

    @NotBlank(message = "Entity ID is required")
    String entityId
) {}

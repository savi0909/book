package com.aaztra.mockpg.dto;

import java.util.List;

/**
 * Response DTO for listing webhooks with summary statistics.
 */
public record WebhookListResponse(
    List<WebhookSummary> webhooks,
    int total,
    int pending,
    int delivered,
    int failed
) {
    /**
     * Summary of a single webhook for list display.
     */
    public record WebhookSummary(
        String eventId,
        String eventType,
        String status,
        int attempts,
        String createdAt,
        String deliveredAt
    ) {}
}

package com.aaztra.mockpg.dto;

import com.aaztra.mockpg.entity.MockWebhook;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * Response DTO for detailed webhook information.
 */
public record WebhookDetailResponse(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("event_type") String eventType,
    Map<String, Object> payload,
    @JsonProperty("webhook_url") String webhookUrl,
    String status,
    int attempts,
    @JsonProperty("max_attempts") int maxAttempts,
    @JsonProperty("last_attempt_at") Instant lastAttemptAt,
    @JsonProperty("last_response_code") Integer lastResponseCode,
    @JsonProperty("last_response_body") String lastResponseBody,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("delivered_at") Instant deliveredAt,
    @JsonProperty("next_retry_at") Instant nextRetryAt
) {
    /**
     * Create a detailed response from MockWebhook entity.
     */
    public static WebhookDetailResponse from(MockWebhook webhook) {
        return new WebhookDetailResponse(
            webhook.getEventId(),
            webhook.getEventType(),
            webhook.getPayload(),
            webhook.getWebhookUrl(),
            webhook.getStatus(),
            webhook.getAttempts(),
            webhook.getMaxAttempts(),
            webhook.getLastAttemptAt(),
            webhook.getLastResponseCode(),
            webhook.getLastResponseBody(),
            webhook.getCreatedAt(),
            webhook.getDeliveredAt(),
            webhook.getNextRetryAt()
        );
    }
}

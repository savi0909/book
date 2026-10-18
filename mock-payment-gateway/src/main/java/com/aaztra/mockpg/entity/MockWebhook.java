package com.aaztra.mockpg.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Mock Webhook entity - tracks webhook delivery to FitPro
 * Schema: payment_processor
 */
@Entity
@Table(name = "mock_webhooks", schema = "payment_processor")
@Getter
@Setter
public class MockWebhook {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true, length = 50)
    private String eventId;  // evt_mock_xxxxxxxxxx

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;  // payment.captured, refund.processed, etc.

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "webhook_url", nullable = false, length = 500)
    private String webhookUrl;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "pending";  // pending/delivered/failed

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts = 3;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_response_code")
    private Integer lastResponseCode;

    @Column(name = "last_response_body", columnDefinition = "TEXT")
    private String lastResponseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // Business methods

    /**
     * Record a successful delivery.
     */
    public void recordSuccessfulDelivery(int responseCode, String responseBody) {
        this.status = "delivered";
        this.attempts++;
        this.lastAttemptAt = Instant.now();
        this.lastResponseCode = responseCode;
        this.lastResponseBody = truncateResponse(responseBody);
        this.deliveredAt = Instant.now();
        this.nextRetryAt = null;
        this.updatedAt = Instant.now();
    }

    /**
     * Record a failed attempt.
     */
    public void recordFailedAttempt(int responseCode, String responseBody) {
        this.attempts++;
        this.lastAttemptAt = Instant.now();
        this.lastResponseCode = responseCode;
        this.lastResponseBody = truncateResponse(responseBody);
        this.updatedAt = Instant.now();
    }

    /**
     * Legacy method - kept for backwards compatibility.
     */
    public void recordAttempt(Integer responseCode, String responseBody) {
        this.attempts++;
        this.lastAttemptAt = Instant.now();
        this.lastResponseCode = responseCode;
        this.lastResponseBody = truncateResponse(responseBody);

        if (responseCode != null && responseCode >= 200 && responseCode < 300) {
            this.status = "delivered";
            this.deliveredAt = Instant.now();
        } else {
            if (this.attempts >= this.maxAttempts) {
                this.status = "failed";
            } else {
                this.status = "pending";
                // Exponential backoff: 1m, 5m, 15m
                long backoffSeconds = (long) Math.pow(5, this.attempts) * 60;
                this.nextRetryAt = Instant.now().plusSeconds(backoffSeconds);
            }
        }
        this.updatedAt = Instant.now();
    }

    /**
     * Check if retry is possible.
     */
    public boolean canRetry() {
        return this.attempts < this.maxAttempts && !"delivered".equals(this.status);
    }

    /**
     * Truncate response body to prevent DB overflow.
     */
    private String truncateResponse(String response) {
        if (response == null) return null;
        return response.length() > 2000 ? response.substring(0, 2000) + "..." : response;
    }
}

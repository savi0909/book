package com.aaztra.mockpg.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mock Refund entity - simulates Razorpay Refund
 * Schema: payment_processor
 */
@Entity
@Table(name = "mock_refunds", schema = "payment_processor")
@Getter
@Setter
public class MockRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "refund_id", nullable = false, unique = true, length = 50)
    private String refundId;  // rfnd_mock_xxxxxxxxxx

    @Column(name = "payment_id", nullable = false, length = 50)
    private String paymentId;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "receipt", length = 100)
    private String receipt;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "created";  // created/processed/failed

    @Column(name = "speed", length = 20)
    private String speed = "normal";  // normal/instant

    @Column(name = "speed_requested", length = 20)
    private String speedRequested = "normal";

    @Column(name = "speed_processed", length = 20)
    private String speedProcessed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notes", columnDefinition = "jsonb")
    private Map<String, String> notes = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "acquirer_data", columnDefinition = "jsonb")
    private Map<String, String> acquirerData = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;  // Unix timestamp

    @Column(name = "processed_at")
    private Long processedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // Business methods

    public void markAsProcessed() {
        this.status = "processed";
        this.processedAt = Instant.now().getEpochSecond();
        this.speedProcessed = this.speedRequested;
        this.updatedAt = Instant.now();
    }

    public void markAsFailed() {
        this.status = "failed";
        this.updatedAt = Instant.now();
    }

    public boolean isProcessed() {
        return "processed".equals(this.status);
    }
}

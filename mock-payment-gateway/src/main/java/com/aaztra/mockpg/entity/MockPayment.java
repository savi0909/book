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
 * Mock Payment entity - simulates Razorpay Payment
 * Schema: payment_processor
 */
@Entity
@Table(name = "mock_payments", schema = "payment_processor")
@Getter
@Setter
public class MockPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_id", nullable = false, unique = true, length = 50)
    private String paymentId;  // pay_mock_xxxxxxxxxx

    @Column(name = "order_id", nullable = false, length = 50)
    private String orderId;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "status", nullable = false, length = 20)
    private String status = "created";  // created/authorized/captured/failed/refunded

    @Column(name = "method", nullable = false, length = 30)
    private String method = "wallet";  // wallet/card/upi/netbanking

    @Column(name = "wallet", length = 50)
    private String wallet;

    @Column(name = "vpa", length = 100)
    private String vpa;

    @Column(name = "card_id", length = 50)
    private String cardId;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "card_network", length = 20)
    private String cardNetwork;

    @Column(name = "card_type", length = 20)
    private String cardType;

    @Column(name = "bank", length = 100)
    private String bank;

    @Column(name = "international")
    private Boolean international = false;

    @Column(name = "amount_refunded")
    private Long amountRefunded = 0L;

    @Column(name = "refund_status", length = 20)
    private String refundStatus;  // null/partial/full

    @Column(name = "captured")
    private Boolean captured = false;

    @Column(name = "captured_at")
    private Long capturedAt;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "contact", length = 20)
    private String contact;

    @Column(name = "fee")
    private Long fee = 0L;  // Simulated gateway fee

    @Column(name = "tax")
    private Long tax = 0L;  // GST on fee

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_description", length = 500)
    private String errorDescription;

    @Column(name = "error_source", length = 50)
    private String errorSource;

    @Column(name = "error_step", length = 50)
    private String errorStep;

    @Column(name = "error_reason", length = 100)
    private String errorReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notes", columnDefinition = "jsonb")
    private Map<String, String> notes = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;  // Unix timestamp

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // Business methods

    public void markAsCaptured() {
        this.captured = true;
        this.status = "captured";
        this.capturedAt = Instant.now().getEpochSecond();
        this.updatedAt = Instant.now();
    }

    public void markAsFailed(String errorCode, String errorDescription) {
        this.status = "failed";
        this.errorCode = errorCode;
        this.errorDescription = errorDescription;
        this.updatedAt = Instant.now();
    }

    public boolean isSuccessful() {
        return "captured".equals(this.status);
    }
}

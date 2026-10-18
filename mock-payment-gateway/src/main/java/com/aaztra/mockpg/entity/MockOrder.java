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
 * Mock Order entity - simulates Razorpay Order
 * Schema: payment_processor
 */
@Entity
@Table(name = "mock_orders", schema = "payment_processor")
@Getter
@Setter
public class MockOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true, length = 50)
    private String orderId;  // order_mock_xxxxxxxxxx

    @Column(name = "amount", nullable = false)
    private Long amount;  // In paise

    @Column(name = "amount_paid", nullable = false)
    private Long amountPaid = 0L;

    @Column(name = "amount_due", nullable = false)
    private Long amountDue;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "receipt", length = 100)
    private String receipt;  // FitPro order ID

    @Column(name = "status", nullable = false, length = 20)
    private String status = "created";  // created, attempted, paid

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notes", columnDefinition = "jsonb")
    private Map<String, String> notes = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private Long createdAt;  // Unix timestamp (Razorpay style)

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // Business methods

    public void recordPaymentAttempt() {
        this.attempts++;
        if ("created".equals(this.status)) {
            this.status = "attempted";
        }
        this.updatedAt = Instant.now();
    }

    public void markAsPaid(Long paidAmount) {
        this.amountPaid = paidAmount;
        this.amountDue = this.amount - paidAmount;
        if (this.amountDue <= 0) {
            this.status = "paid";
            this.amountDue = 0L;
        }
        this.updatedAt = Instant.now();
    }

    public boolean canAcceptPayment() {
        return !"paid".equals(this.status) && this.amountDue > 0;
    }
}

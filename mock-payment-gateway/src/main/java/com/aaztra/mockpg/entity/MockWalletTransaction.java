package com.aaztra.mockpg.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Mock Wallet Transaction entity - audit trail for wallet operations
 * Schema: payment_processor
 */
@Entity
@Table(name = "mock_wallet_transactions", schema = "payment_processor")
@Getter
@Setter
public class MockWalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "wallet_id", nullable = false, length = 100)
    private String walletId;

    @Column(name = "transaction_type", nullable = false, length = 20)
    private String transactionType;  // debit/credit/refund_credit

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "balance_after", nullable = false)
    private Long balanceAfter;

    @Column(name = "reference_type", length = 20)
    private String referenceType;  // payment/refund/topup

    @Column(name = "reference_id", length = 50)
    private String referenceId;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}

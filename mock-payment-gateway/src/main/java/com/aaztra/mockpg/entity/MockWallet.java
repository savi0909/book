package com.aaztra.mockpg.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Mock Wallet entity - simulated wallet balances for testing
 * Schema: payment_processor
 */
@Entity
@Table(name = "mock_wallets", schema = "payment_processor")
@Getter
@Setter
public class MockWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "wallet_id", nullable = false, unique = true, length = 100)
    private String walletId;  // Phone or email

    @Column(name = "provider", nullable = false, length = 50)
    private String provider = "mock_wallet";

    @Column(name = "balance", nullable = false)
    private Long balance = 100000000L;  // ₹10,00,000 default

    @Column(name = "status", nullable = false, length = 20)
    private String status = "active";  // active/blocked

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    // Business methods

    public boolean canDebit(Long amount) {
        return "active".equals(this.status) && this.balance >= amount;
    }

    public void debit(Long amount) {
        if (!canDebit(amount)) {
            throw new IllegalStateException("Insufficient balance or wallet blocked");
        }
        this.balance -= amount;
        this.updatedAt = Instant.now();
    }

    public void credit(Long amount) {
        this.balance += amount;
        this.updatedAt = Instant.now();
    }

    public boolean isActive() {
        return "active".equals(this.status);
    }
}

package com.aaztra.mockpg.service;

import com.aaztra.mockpg.entity.MockWallet;
import com.aaztra.mockpg.repository.MockWalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Service for managing mock wallets.
 */
@Service
@Transactional
public class MockWalletService {

    private static final Logger log = LoggerFactory.getLogger(MockWalletService.class);
    private static final long DEFAULT_BALANCE = 100_000_00L; // ₹1,00,000

    private final MockWalletRepository walletRepository;

    public MockWalletService(MockWalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    /**
     * Get or create a wallet by ID.
     * Creates a new wallet with default balance if not exists.
     */
    public MockWallet getOrCreateWallet(String walletId) {
        return walletRepository.findByWalletId(walletId)
            .orElseGet(() -> createWallet(walletId, DEFAULT_BALANCE, "active"));
    }

    /**
     * Create a new wallet with specified balance and status.
     */
    public MockWallet createWallet(String walletId, Long balance, String status) {
        log.info("Creating wallet: {} with balance: {}", walletId, balance);

        MockWallet wallet = new MockWallet();
        wallet.setWalletId(walletId);
        wallet.setProvider("mock_wallet");
        wallet.setBalance(balance != null ? balance : DEFAULT_BALANCE);
        wallet.setStatus(status != null ? status : "active");
        wallet.setCreatedAt(Instant.now());
        wallet.setUpdatedAt(Instant.now());

        return walletRepository.save(wallet);
    }

    /**
     * Update wallet balance.
     */
    public MockWallet updateBalance(String walletId, Long newBalance) {
        log.info("Updating wallet {} balance to {}", walletId, newBalance);

        MockWallet wallet = walletRepository.findByWalletId(walletId)
            .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

        wallet.setBalance(newBalance);
        wallet.setUpdatedAt(Instant.now());

        return walletRepository.save(wallet);
    }

    /**
     * Update wallet status (active/blocked).
     */
    public MockWallet updateStatus(String walletId, String status) {
        log.info("Updating wallet {} status to {}", walletId, status);

        if (!status.equals("active") && !status.equals("blocked")) {
            throw new IllegalArgumentException("Status must be 'active' or 'blocked'");
        }

        MockWallet wallet = walletRepository.findByWalletId(walletId)
            .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + walletId));

        wallet.setStatus(status);
        wallet.setUpdatedAt(Instant.now());

        return walletRepository.save(wallet);
    }
}

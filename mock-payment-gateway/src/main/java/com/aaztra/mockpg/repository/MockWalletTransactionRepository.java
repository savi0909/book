package com.aaztra.mockpg.repository;

import com.aaztra.mockpg.entity.MockWalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MockWalletTransactionRepository extends JpaRepository<MockWalletTransaction, UUID> {

    List<MockWalletTransaction> findByWalletIdOrderByCreatedAtDesc(String walletId);
}

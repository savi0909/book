package com.aaztra.mockpg.repository;

import com.aaztra.mockpg.entity.MockWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MockWalletRepository extends JpaRepository<MockWallet, UUID> {

    Optional<MockWallet> findByWalletId(String walletId);
}

package com.aaztra.mockpg.repository;

import com.aaztra.mockpg.entity.MockRefund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MockRefundRepository extends JpaRepository<MockRefund, UUID> {

    Optional<MockRefund> findByRefundId(String refundId);

    List<MockRefund> findByPaymentId(String paymentId);
}

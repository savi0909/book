package com.aaztra.mockpg.repository;

import com.aaztra.mockpg.entity.MockPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MockPaymentRepository extends JpaRepository<MockPayment, UUID> {

    Optional<MockPayment> findByPaymentId(String paymentId);

    List<MockPayment> findByOrderId(String orderId);
}

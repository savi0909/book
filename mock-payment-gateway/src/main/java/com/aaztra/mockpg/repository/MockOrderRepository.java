package com.aaztra.mockpg.repository;

import com.aaztra.mockpg.entity.MockOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MockOrderRepository extends JpaRepository<MockOrder, UUID> {

    Optional<MockOrder> findByOrderId(String orderId);

    List<MockOrder> findByReceipt(String receipt);
}

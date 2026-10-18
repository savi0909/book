package com.aaztra.mockpg.service;

import com.aaztra.mockpg.dto.CreateMockOrderRequest;
import com.aaztra.mockpg.dto.MockOrderResponse;
import com.aaztra.mockpg.entity.MockOrder;
import com.aaztra.mockpg.exception.MockOrderNotFoundException;
import com.aaztra.mockpg.exception.OrderAlreadyPaidException;
import com.aaztra.mockpg.repository.MockOrderRepository;
import com.aaztra.mockpg.util.MockIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class MockOrderService {

    private static final Logger log = LoggerFactory.getLogger(MockOrderService.class);

    private final MockOrderRepository orderRepository;
    private final MockIdGenerator idGenerator;

    public MockOrderService(MockOrderRepository orderRepository, MockIdGenerator idGenerator) {
        this.orderRepository = orderRepository;
        this.idGenerator = idGenerator;
    }

    /**
     * Create a new mock order.
     */
    public MockOrderResponse createOrder(CreateMockOrderRequest request) {
        log.info("Creating mock order: amount={}, receipt={}", request.amount(), request.receipt());

        MockOrder order = new MockOrder();
        order.setOrderId(idGenerator.generateOrderId());
        order.setAmount(request.amount());
        order.setAmountPaid(0L);
        order.setAmountDue(request.amount());
        order.setCurrency(request.currency());
        order.setReceipt(request.receipt());
        order.setStatus("created");
        order.setAttempts(0);
        order.setNotes(request.notes());
        order.setCreatedAt(Instant.now().getEpochSecond());
        order.setUpdatedAt(Instant.now());

        MockOrder saved = orderRepository.save(order);
        log.info("Created mock order: {}", saved.getOrderId());

        return MockOrderResponse.from(saved);
    }

    /**
     * Get order by gateway order ID.
     */
    @Transactional(readOnly = true)
    public MockOrderResponse getOrder(String orderId) {
        MockOrder order = orderRepository.findByOrderId(orderId)
            .orElseThrow(() -> new MockOrderNotFoundException(orderId));
        return MockOrderResponse.from(order);
    }

    /**
     * Get all orders for a receipt (FitPro order ID).
     */
    @Transactional(readOnly = true)
    public List<MockOrderResponse> getOrdersByReceipt(String receipt) {
        return orderRepository.findByReceipt(receipt).stream()
            .map(MockOrderResponse::from)
            .collect(Collectors.toList());
    }

    /**
     * Record payment attempt against order.
     */
    public void recordPaymentAttempt(String orderId) {
        MockOrder order = orderRepository.findByOrderId(orderId)
            .orElseThrow(() -> new MockOrderNotFoundException(orderId));

        order.recordPaymentAttempt();
        orderRepository.save(order);

        log.debug("Recorded payment attempt for order: {}, total attempts: {}",
                  orderId, order.getAttempts());
    }

    /**
     * Mark order as paid.
     */
    public void markOrderAsPaid(String orderId, Long amountPaid) {
        MockOrder order = orderRepository.findByOrderId(orderId)
            .orElseThrow(() -> new MockOrderNotFoundException(orderId));

        if ("paid".equals(order.getStatus())) {
            throw new OrderAlreadyPaidException(orderId);
        }

        order.markAsPaid(amountPaid);
        orderRepository.save(order);

        log.info("Marked order as paid: {}, amount: {}", orderId, amountPaid);
    }

    /**
     * Get order entity by ID (internal use).
     */
    @Transactional(readOnly = true)
    public MockOrder getOrderEntity(String orderId) {
        return orderRepository.findByOrderId(orderId)
            .orElseThrow(() -> new MockOrderNotFoundException(orderId));
    }
}

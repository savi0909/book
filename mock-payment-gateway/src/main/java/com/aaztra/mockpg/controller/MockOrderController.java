package com.aaztra.mockpg.controller;

import com.aaztra.mockpg.dto.CreateMockOrderRequest;
import com.aaztra.mockpg.dto.MockOrderResponse;
import com.aaztra.mockpg.service.MockOrderService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Mock Order Controller - Razorpay Orders API simulation.
 *
 * Endpoints:
 * - POST /mock-pg/v1/orders - Create order
 * - GET /mock-pg/v1/orders/{orderId} - Get order
 * - GET /mock-pg/v1/orders?receipt={receipt} - Get orders by receipt
 */
@RestController
@RequestMapping("/mock-pg/v1/orders")
public class MockOrderController {

    private static final Logger log = LoggerFactory.getLogger(MockOrderController.class);

    private final MockOrderService orderService;

    public MockOrderController(MockOrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Create a new mock order.
     *
     * Razorpay equivalent: POST /v1/orders
     */
    @PostMapping
    public ResponseEntity<MockOrderResponse> createOrder(
            @Valid @RequestBody CreateMockOrderRequest request) {
        log.info("Creating mock order: amount={} paise, receipt={}",
                 request.amount(), request.receipt());

        MockOrderResponse response = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get order by ID.
     *
     * Razorpay equivalent: GET /v1/orders/{order_id}
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<MockOrderResponse> getOrder(@PathVariable String orderId) {
        log.debug("Getting mock order: {}", orderId);
        return ResponseEntity.ok(orderService.getOrder(orderId));
    }

    /**
     * Get orders by receipt (FitPro order ID).
     */
    @GetMapping
    public ResponseEntity<List<MockOrderResponse>> getOrdersByReceipt(
            @RequestParam(required = false) String receipt) {
        if (receipt != null) {
            log.debug("Getting mock orders by receipt: {}", receipt);
            return ResponseEntity.ok(orderService.getOrdersByReceipt(receipt));
        }
        // Return empty list if no filter (Razorpay returns paginated list)
        return ResponseEntity.ok(List.of());
    }
}

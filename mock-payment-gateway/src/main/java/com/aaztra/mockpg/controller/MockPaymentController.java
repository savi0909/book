package com.aaztra.mockpg.controller;

import com.aaztra.mockpg.dto.CapturePaymentRequest;
import com.aaztra.mockpg.dto.MockPaymentResponse;
import com.aaztra.mockpg.dto.SimulatePaymentRequest;
import com.aaztra.mockpg.service.MockPaymentService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Mock Payment Controller - Razorpay Payments API simulation.
 *
 * Endpoints:
 * - POST /mock-pg/v1/orders/{orderId}/payments - Simulate payment (TEST ONLY)
 * - GET /mock-pg/v1/payments/{paymentId} - Get payment
 * - POST /mock-pg/v1/payments/{paymentId}/capture - Capture payment
 * - GET /mock-pg/v1/orders/{orderId}/payments - Get payments for order
 * - GET /mock-pg/v1/payments/{paymentId}/refundable - Get refundable amount
 */
@RestController
@RequestMapping("/mock-pg/v1")
public class MockPaymentController {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentController.class);

    private final MockPaymentService paymentService;

    public MockPaymentController(MockPaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Simulate a payment completion.
     *
     * NOTE: This is a TEST-ONLY endpoint. In production, Razorpay SDK
     * handles payment on the client side, and we receive webhooks.
     */
    @PostMapping("/orders/{orderId}/payments")
    public ResponseEntity<MockPaymentResponse> simulatePayment(
            @PathVariable String orderId,
            @Valid @RequestBody SimulatePaymentRequest request) {
        log.info("Simulating payment for order: {}, method: {}", orderId, request.method());

        MockPaymentResponse response = paymentService.simulatePayment(orderId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Get payment by ID.
     *
     * Razorpay equivalent: GET /v1/payments/{payment_id}
     */
    @GetMapping("/payments/{paymentId}")
    public ResponseEntity<MockPaymentResponse> getPayment(@PathVariable String paymentId) {
        log.debug("Getting payment: {}", paymentId);
        return ResponseEntity.ok(paymentService.getPayment(paymentId));
    }

    /**
     * Capture an authorized payment.
     *
     * Razorpay equivalent: POST /v1/payments/{payment_id}/capture
     */
    @PostMapping("/payments/{paymentId}/capture")
    public ResponseEntity<MockPaymentResponse> capturePayment(
            @PathVariable String paymentId,
            @Valid @RequestBody CapturePaymentRequest request) {
        log.info("Capturing payment: {}, amount: {}", paymentId, request.amount());

        MockPaymentResponse response = paymentService.capturePayment(paymentId, request.amount());
        return ResponseEntity.ok(response);
    }

    /**
     * Get all payments for an order.
     */
    @GetMapping("/orders/{orderId}/payments")
    public ResponseEntity<List<MockPaymentResponse>> getPaymentsForOrder(
            @PathVariable String orderId) {
        log.debug("Getting payments for order: {}", orderId);
        return ResponseEntity.ok(paymentService.getPaymentsByOrderId(orderId));
    }

    /**
     * Get refundable amount for a payment.
     */
    @GetMapping("/payments/{paymentId}/refundable")
    public ResponseEntity<Map<String, Long>> getRefundableAmount(@PathVariable String paymentId) {
        Long refundable = paymentService.getRefundableAmount(paymentId);
        return ResponseEntity.ok(Map.of("refundable_amount", refundable));
    }
}

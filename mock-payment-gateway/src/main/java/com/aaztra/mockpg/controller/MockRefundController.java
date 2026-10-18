package com.aaztra.mockpg.controller;

import com.aaztra.mockpg.dto.CreateMockRefundRequest;
import com.aaztra.mockpg.dto.MockRefundResponse;
import com.aaztra.mockpg.service.MockRefundService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Mock Refund Controller - Razorpay Refunds API simulation.
 *
 * Endpoints:
 * - POST /mock-pg/v1/payments/{paymentId}/refund - Create refund
 * - GET /mock-pg/v1/refunds/{refundId} - Get refund
 * - GET /mock-pg/v1/payments/{paymentId}/refunds - Get refunds for payment
 */
@RestController
@RequestMapping("/mock-pg/v1")
public class MockRefundController {

    private static final Logger log = LoggerFactory.getLogger(MockRefundController.class);

    private final MockRefundService refundService;

    public MockRefundController(MockRefundService refundService) {
        this.refundService = refundService;
    }

    /**
     * Create a refund for a payment.
     *
     * Razorpay equivalent: POST /v1/payments/{payment_id}/refund
     */
    @PostMapping("/payments/{paymentId}/refund")
    public ResponseEntity<MockRefundResponse> createRefund(
            @PathVariable String paymentId,
            @Valid @RequestBody CreateMockRefundRequest request) {
        log.info("Creating refund for payment: {}, amount: {} paise", paymentId, request.amount());

        MockRefundResponse response = refundService.createRefund(paymentId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get refund by ID.
     *
     * Razorpay equivalent: GET /v1/refunds/{refund_id}
     */
    @GetMapping("/refunds/{refundId}")
    public ResponseEntity<MockRefundResponse> getRefund(@PathVariable String refundId) {
        log.debug("Getting refund: {}", refundId);
        return ResponseEntity.ok(refundService.getRefund(refundId));
    }

    /**
     * Get all refunds for a payment.
     */
    @GetMapping("/payments/{paymentId}/refunds")
    public ResponseEntity<List<MockRefundResponse>> getRefundsForPayment(
            @PathVariable String paymentId) {
        log.debug("Getting refunds for payment: {}", paymentId);
        return ResponseEntity.ok(refundService.getRefundsByPaymentId(paymentId));
    }
}

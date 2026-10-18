package com.aaztra.mockpg.exception;

public class RefundExceedsPaymentException extends MockPaymentException {
    public RefundExceedsPaymentException(Long refundAmount, Long availableAmount) {
        super("BAD_REQUEST_ERROR",
              String.format("Refund amount %d exceeds available amount %d", refundAmount, availableAmount),
              "business",
              "refund_exceeds_payment");
    }
}

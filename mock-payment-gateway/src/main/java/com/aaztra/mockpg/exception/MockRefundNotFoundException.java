package com.aaztra.mockpg.exception;

public class MockRefundNotFoundException extends MockPaymentException {
    public MockRefundNotFoundException(String refundId) {
        super("BAD_REQUEST_ERROR",
              "Refund with id " + refundId + " not found",
              "business",
              "refund_not_found");
    }
}

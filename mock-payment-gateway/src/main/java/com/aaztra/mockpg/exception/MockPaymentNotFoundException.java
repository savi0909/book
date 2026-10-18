package com.aaztra.mockpg.exception;

public class MockPaymentNotFoundException extends MockPaymentException {
    public MockPaymentNotFoundException(String paymentId) {
        super("BAD_REQUEST_ERROR",
              "Payment with id " + paymentId + " not found",
              "business",
              "payment_not_found");
    }
}

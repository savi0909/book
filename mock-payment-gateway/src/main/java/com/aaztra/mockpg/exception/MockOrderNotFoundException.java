package com.aaztra.mockpg.exception;

public class MockOrderNotFoundException extends MockPaymentException {
    public MockOrderNotFoundException(String orderId) {
        super("BAD_REQUEST_ERROR",
              "Order with id " + orderId + " not found",
              "business",
              "order_not_found");
    }
}

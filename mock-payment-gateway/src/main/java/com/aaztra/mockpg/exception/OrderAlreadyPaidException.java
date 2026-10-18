package com.aaztra.mockpg.exception;

public class OrderAlreadyPaidException extends MockPaymentException {
    public OrderAlreadyPaidException(String orderId) {
        super("BAD_REQUEST_ERROR",
              "Order " + orderId + " has already been paid",
              "business",
              "order_already_paid");
    }
}

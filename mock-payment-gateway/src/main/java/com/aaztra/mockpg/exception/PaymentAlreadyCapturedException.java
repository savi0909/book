package com.aaztra.mockpg.exception;

public class PaymentAlreadyCapturedException extends MockPaymentException {
    public PaymentAlreadyCapturedException(String paymentId) {
        super("BAD_REQUEST_ERROR",
              "Payment " + paymentId + " has already been captured",
              "business",
              "payment_already_captured");
    }
}

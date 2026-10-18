package com.aaztra.mockpg.exception;

public class InvalidPaymentStateException extends MockPaymentException {
    public InvalidPaymentStateException(String paymentId, String currentState, String expectedState) {
        super("BAD_REQUEST_ERROR",
              String.format("Payment %s is in state '%s', expected '%s'", paymentId, currentState, expectedState),
              "business",
              "invalid_payment_state");
    }
}

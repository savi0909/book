package com.aaztra.mockpg.exception;

public class InsufficientBalanceException extends MockPaymentException {
    public InsufficientBalanceException(String walletId) {
        super("BAD_REQUEST_ERROR",
              "Insufficient balance in wallet " + walletId,
              "customer",
              "insufficient_balance");
    }
}

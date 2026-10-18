package com.aaztra.mockpg.exception;

/**
 * Base exception for mock payment processor.
 */
public class MockPaymentException extends RuntimeException {

    private final String errorCode;
    private final String errorSource;
    private final String errorReason;

    public MockPaymentException(String message) {
        super(message);
        this.errorCode = "INTERNAL_ERROR";
        this.errorSource = "internal";
        this.errorReason = "server_error";
    }

    public MockPaymentException(String errorCode, String message, String errorSource, String errorReason) {
        super(message);
        this.errorCode = errorCode;
        this.errorSource = errorSource;
        this.errorReason = errorReason;
    }

    public String getErrorCode() { return errorCode; }
    public String getErrorSource() { return errorSource; }
    public String getErrorReason() { return errorReason; }
}

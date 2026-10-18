package com.aaztra.mockpg.exception;

import com.aaztra.mockpg.dto.MockErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Exception handler for Mock Payment Processor APIs.
 * Returns Razorpay-compatible error responses.
 */
@RestControllerAdvice(basePackages = "com.aaztra.mockpg")
@Order(1)  // Higher priority than global handler
public class MockPaymentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentExceptionHandler.class);

    @ExceptionHandler(MockOrderNotFoundException.class)
    public ResponseEntity<MockErrorResponse> handleOrderNotFound(MockOrderNotFoundException ex) {
        log.warn("Order not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(MockErrorResponse.of(ex.getErrorCode(), ex.getMessage(), ex.getErrorSource(), ex.getErrorReason()));
    }

    @ExceptionHandler(MockPaymentNotFoundException.class)
    public ResponseEntity<MockErrorResponse> handlePaymentNotFound(MockPaymentNotFoundException ex) {
        log.warn("Payment not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(MockErrorResponse.of(ex.getErrorCode(), ex.getMessage(), ex.getErrorSource(), ex.getErrorReason()));
    }

    @ExceptionHandler(MockRefundNotFoundException.class)
    public ResponseEntity<MockErrorResponse> handleRefundNotFound(MockRefundNotFoundException ex) {
        log.warn("Refund not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(MockErrorResponse.of(ex.getErrorCode(), ex.getMessage(), ex.getErrorSource(), ex.getErrorReason()));
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<MockErrorResponse> handleInsufficientBalance(InsufficientBalanceException ex) {
        log.warn("Insufficient balance: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(MockErrorResponse.of(ex.getErrorCode(), ex.getMessage(), ex.getErrorSource(), ex.getErrorReason()));
    }

    @ExceptionHandler(PaymentAlreadyCapturedException.class)
    public ResponseEntity<MockErrorResponse> handleAlreadyCaptured(PaymentAlreadyCapturedException ex) {
        log.warn("Payment already captured: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(MockErrorResponse.of(ex.getErrorCode(), ex.getMessage(), ex.getErrorSource(), ex.getErrorReason()));
    }

    @ExceptionHandler(InvalidPaymentStateException.class)
    public ResponseEntity<MockErrorResponse> handleInvalidState(InvalidPaymentStateException ex) {
        log.warn("Invalid payment state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(MockErrorResponse.of(ex.getErrorCode(), ex.getMessage(), ex.getErrorSource(), ex.getErrorReason()));
    }

    @ExceptionHandler(OrderAlreadyPaidException.class)
    public ResponseEntity<MockErrorResponse> handleOrderAlreadyPaid(OrderAlreadyPaidException ex) {
        log.warn("Order already paid: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(MockErrorResponse.of(ex.getErrorCode(), ex.getMessage(), ex.getErrorSource(), ex.getErrorReason()));
    }

    @ExceptionHandler(RefundExceedsPaymentException.class)
    public ResponseEntity<MockErrorResponse> handleRefundExceeds(RefundExceedsPaymentException ex) {
        log.warn("Refund exceeds payment: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(MockErrorResponse.of(ex.getErrorCode(), ex.getMessage(), ex.getErrorSource(), ex.getErrorReason()));
    }

    @ExceptionHandler(MockPaymentException.class)
    public ResponseEntity<MockErrorResponse> handleMockPaymentException(MockPaymentException ex) {
        log.error("Mock payment error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(MockErrorResponse.of(ex.getErrorCode(), ex.getMessage(), ex.getErrorSource(), ex.getErrorReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<MockErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String field = ex.getBindingResult().getFieldErrors().isEmpty()
            ? "unknown"
            : ex.getBindingResult().getFieldErrors().get(0).getField();
        String message = ex.getBindingResult().getFieldErrors().isEmpty()
            ? "Validation failed"
            : ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage();

        log.warn("Validation error: {} - {}", field, message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(MockErrorResponse.badRequest(message, field));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<MockErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Invalid argument: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(MockErrorResponse.badRequest(ex.getMessage(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<MockErrorResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error in mock payment processor", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(MockErrorResponse.of("SERVER_ERROR", "An internal error occurred"));
    }
}

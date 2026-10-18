package com.aaztra.mockpg.util;

import org.springframework.stereotype.Component;

/**
 * Resolves magic amounts for deterministic testing.
 *
 * Amounts are in rupees. Magic outcomes are triggered by the last two digits:
 * - 99 → Payment fails               (e.g. ₹3999, ₹199)
 * - 88 → Insufficient balance        (e.g. ₹388, ₹1088)
 * - 77 → Gateway timeout             (e.g. ₹277, ₹5077)
 * - 66 → Delayed webhook (30s)       (e.g. ₹166, ₹2066)
 * - 55 → Authorization only          (e.g. ₹455, ₹1055)
 * - 45 → Partial capture (80%)       (e.g. ₹345, ₹1045)
 * - 44 → Duplicate webhook (3x)      (e.g. ₹344, ₹1044)
 * - 33 → Concurrent capture (2x)     (e.g. ₹233, ₹1033)
 * - 22 → Late webhook (35 min)       (e.g. ₹122, ₹1022)
 * - 11 → Refund fails                (e.g. ₹311, ₹5011)
 * - Any other → Success
 */
@Component
public class MagicAmountResolver {

    public enum SimulatedOutcome {
        SUCCESS,
        PAYMENT_FAILED,
        INSUFFICIENT_BALANCE,
        TIMEOUT,
        DELAYED_WEBHOOK,
        AUTHORIZATION_ONLY,
        REFUND_FAILED,
        DUPLICATE_WEBHOOK,
        CONCURRENT_CAPTURE,
        LATE_WEBHOOK,
        PARTIAL_CAPTURE
    }

    public SimulatedOutcome resolvePaymentOutcome(long amount) {
        int lastTwoDigits = (int) (amount % 100);
        return switch (lastTwoDigits) {
            case 99 -> SimulatedOutcome.PAYMENT_FAILED;
            case 88 -> SimulatedOutcome.INSUFFICIENT_BALANCE;
            case 77 -> SimulatedOutcome.TIMEOUT;
            case 66 -> SimulatedOutcome.DELAYED_WEBHOOK;
            case 55 -> SimulatedOutcome.AUTHORIZATION_ONLY;
            case 45 -> SimulatedOutcome.PARTIAL_CAPTURE;
            case 44 -> SimulatedOutcome.DUPLICATE_WEBHOOK;
            case 33 -> SimulatedOutcome.CONCURRENT_CAPTURE;
            case 22 -> SimulatedOutcome.LATE_WEBHOOK;
            default -> SimulatedOutcome.SUCCESS;
        };
    }

    public SimulatedOutcome resolveRefundOutcome(long amount) {
        int lastTwoDigits = (int) (amount % 100);
        return lastTwoDigits == 11 ? SimulatedOutcome.REFUND_FAILED : SimulatedOutcome.SUCCESS;
    }

    public record ErrorDetails(
            String errorCode,
            String errorDescription,
            String errorSource,
            String errorStep,
            String errorReason
    ) {}

    public ErrorDetails getErrorDetails(SimulatedOutcome outcome) {
        return switch (outcome) {
            case PAYMENT_FAILED -> new ErrorDetails(
                    "BAD_REQUEST_ERROR",
                    "Payment processing failed. Please try again.",
                    "bank",
                    "payment_authorization",
                    "payment_failed"
            );
            case INSUFFICIENT_BALANCE -> new ErrorDetails(
                    "BAD_REQUEST_ERROR",
                    "Insufficient wallet balance.",
                    "customer",
                    "payment_initiation",
                    "insufficient_balance"
            );
            case TIMEOUT -> new ErrorDetails(
                    "GATEWAY_ERROR",
                    "Gateway did not respond in time.",
                    "gateway",
                    "payment_authorization",
                    "gateway_timeout"
            );
            default -> null;
        };
    }

    public long getWebhookDelayMs(SimulatedOutcome outcome) {
        return switch (outcome) {
            case DELAYED_WEBHOOK -> 30_000L;
            case LATE_WEBHOOK -> 2_100_000L;  // 35 minutes — simulates post-expiry webhook arrival
            default -> 2_000L;
        };
    }
}

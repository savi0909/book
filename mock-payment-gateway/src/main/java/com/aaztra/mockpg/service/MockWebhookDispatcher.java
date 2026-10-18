package com.aaztra.mockpg.service;

import com.aaztra.mockpg.entity.MockPayment;
import com.aaztra.mockpg.entity.MockRefund;
import com.aaztra.mockpg.entity.MockWebhook;

/**
 * Service for dispatching webhooks to FitPro.
 */
public interface MockWebhookDispatcher {

    /**
     * Dispatch payment.captured webhook.
     */
    void dispatchPaymentCaptured(MockPayment payment);

    /**
     * Dispatch payment.authorized webhook.
     */
    void dispatchPaymentAuthorized(MockPayment payment);

    /**
     * Dispatch payment.failed webhook.
     */
    void dispatchPaymentFailed(MockPayment payment);

    /**
     * Dispatch refund.processed webhook.
     */
    void dispatchRefundProcessed(MockRefund refund, MockPayment payment);

    /**
     * Dispatch refund.failed webhook.
     */
    void dispatchRefundFailed(MockRefund refund, MockPayment payment);

    /**
     * Retry a failed webhook.
     */
    void retryWebhook(MockWebhook webhook);

    /**
     * Manually trigger a webhook (admin utility).
     */
    void triggerWebhook(String eventType, String entityId);

    // Legacy methods for backwards compatibility

    /**
     * Dispatch payment.captured webhook with delay.
     * @deprecated Use dispatchPaymentCaptured(payment) instead
     */
    @Deprecated
    default void dispatchPaymentCapturedWebhook(MockPayment payment, long delayMs) {
        dispatchPaymentCaptured(payment);
    }

    /**
     * Dispatch payment.failed webhook with delay.
     * @deprecated Use dispatchPaymentFailed(payment) instead
     */
    @Deprecated
    default void dispatchPaymentFailedWebhook(MockPayment payment, long delayMs) {
        dispatchPaymentFailed(payment);
    }

    /**
     * Dispatch refund.processed webhook with delay.
     * @deprecated Use dispatchRefundProcessed(refund, payment) instead
     */
    @Deprecated
    default void dispatchRefundProcessedWebhook(MockRefund refund, long delayMs) {
        // Default implementation without payment - callers should migrate
    }
}

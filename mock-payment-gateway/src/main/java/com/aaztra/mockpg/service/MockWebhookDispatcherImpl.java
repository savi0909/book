package com.aaztra.mockpg.service;

import com.aaztra.mockpg.config.MockPgProperties;
import com.aaztra.mockpg.entity.MockPayment;
import com.aaztra.mockpg.entity.MockRefund;
import com.aaztra.mockpg.entity.MockWebhook;
import com.aaztra.mockpg.repository.MockPaymentRepository;
import com.aaztra.mockpg.repository.MockRefundRepository;
import com.aaztra.mockpg.repository.MockWebhookRepository;
import com.aaztra.mockpg.util.MagicAmountResolver;
import com.aaztra.mockpg.util.MagicAmountResolver.SimulatedOutcome;
import com.aaztra.mockpg.util.MockIdGenerator;
import com.aaztra.mockpg.util.MockSignatureUtil;
import com.aaztra.mockpg.saga.SagaOrigin;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MockWebhookDispatcherImpl implements MockWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MockWebhookDispatcherImpl.class);

    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final int[] RETRY_DELAYS_SECONDS = {60, 300, 900, 3600, 7200};
    private static final long WEBHOOK_DELAY_MIN_MS = 100L;
    private static final long WEBHOOK_DELAY_MAX_MS = 500L;

    private final MockWebhookRepository webhookRepository;
    private final MockPaymentRepository paymentRepository;
    private final MockRefundRepository refundRepository;
    private final MockConfigService configService;
    private final MockIdGenerator idGenerator;
    private final MockSignatureUtil signatureUtil;
    private final MagicAmountResolver magicAmountResolver;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final MockPgProperties properties;

    public MockWebhookDispatcherImpl(
            MockWebhookRepository webhookRepository,
            MockPaymentRepository paymentRepository,
            MockRefundRepository refundRepository,
            MockConfigService configService,
            MockIdGenerator idGenerator,
            MockSignatureUtil signatureUtil,
            MagicAmountResolver magicAmountResolver,
            ObjectMapper objectMapper,
            @Qualifier("webhookRestClient") RestClient restClient,
            MockPgProperties properties) {
        this.webhookRepository = webhookRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.configService = configService;
        this.idGenerator = idGenerator;
        this.signatureUtil = signatureUtil;
        this.magicAmountResolver = magicAmountResolver;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    @Async("webhookExecutor")
    public void dispatchPaymentCaptured(MockPayment payment) {
        if (SagaOrigin.isSaga(payment.getNotes())) return;
        log.info("Dispatching payment.captured webhook for: {}", payment.getPaymentId());

        SimulatedOutcome outcome = magicAmountResolver.resolvePaymentOutcome(payment.getAmount());
        long delay = calculateDelay(outcome);

        if (delay > 0) {
            log.info("Delaying webhook by {}ms for payment: {}", delay, payment.getPaymentId());
            sleep(delay);
        }

        Map<String, Object> payload = buildPaymentWebhookPayload("payment.captured", payment);

        switch (outcome) {
            case DUPLICATE_WEBHOOK -> {
                // Simulate gateway retry storm: send identical webhook 3 times with 2-second gaps.
                // Tests idempotency guards — FitPro must confirm the order exactly once.
                log.info("MAGIC_AMOUNT_DUPLICATE: Sending webhook 3x for payment: {}", payment.getPaymentId());
                dispatchWebhook(payload, payment.getPaymentId());
                sleep(2000);
                dispatchWebhook(payload, payment.getPaymentId());
                sleep(2000);
                dispatchWebhook(payload, payment.getPaymentId());
            }
            case CONCURRENT_CAPTURE -> {
                // Simulate race condition: send 2 webhooks concurrently on separate threads.
                // Tests that concurrent capture handling doesn't cause double order confirmation.
                log.info("MAGIC_AMOUNT_CONCURRENT: Sending 2 webhooks in parallel for payment: {}", payment.getPaymentId());
                CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> dispatchWebhook(payload, payment.getPaymentId()));
                CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> dispatchWebhook(payload, payment.getPaymentId()));
                CompletableFuture.allOf(f1, f2).join();
            }
            default -> dispatchWebhook(payload, payment.getPaymentId());
        }
    }

    @Override
    @Async("webhookExecutor")
    public void dispatchPaymentAuthorized(MockPayment payment) {
        if (SagaOrigin.isSaga(payment.getNotes())) return;
        log.info("Dispatching payment.authorized webhook for: {}", payment.getPaymentId());

        Map<String, Object> payload = buildPaymentWebhookPayload("payment.authorized", payment);
        dispatchWebhook(payload, payment.getPaymentId());
    }

    @Override
    @Async("webhookExecutor")
    public void dispatchPaymentFailed(MockPayment payment) {
        if (SagaOrigin.isSaga(payment.getNotes())) return;
        log.info("Dispatching payment.failed webhook for: {}", payment.getPaymentId());

        Map<String, Object> payload = buildPaymentWebhookPayload("payment.failed", payment);
        dispatchWebhook(payload, payment.getPaymentId());
    }

    @Override
    @Async("webhookExecutor")
    public void dispatchRefundProcessed(MockRefund refund, MockPayment payment) {
        if (SagaOrigin.isSaga(refund.getNotes()) || SagaOrigin.isSaga(payment.getNotes())) return;
        log.info("Dispatching refund.processed webhook for: {}", refund.getRefundId());

        Map<String, Object> payload = buildRefundWebhookPayload("refund.processed", refund, payment);
        dispatchWebhook(payload, refund.getRefundId());
    }

    @Override
    @Async("webhookExecutor")
    public void dispatchRefundFailed(MockRefund refund, MockPayment payment) {
        if (SagaOrigin.isSaga(refund.getNotes()) || SagaOrigin.isSaga(payment.getNotes())) return;
        log.info("Dispatching refund.failed webhook for: {}", refund.getRefundId());

        Map<String, Object> payload = buildRefundWebhookPayload("refund.failed", refund, payment);
        dispatchWebhook(payload, refund.getRefundId());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryWebhook(MockWebhook webhook) {
        log.info("Retrying webhook: {}, attempt: {}", webhook.getEventId(), webhook.getAttempts() + 1);

        try {
            String payloadJson = objectMapper.writeValueAsString(webhook.getPayload());
            sendWebhookRequest(webhook, payloadJson);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize webhook payload for retry: {}", webhook.getEventId(), e);
            webhook.recordFailedAttempt(500, "Serialization error: " + e.getMessage());
            webhookRepository.save(webhook);
        }
    }

    @Override
    public void triggerWebhook(String eventType, String entityId) {
        log.info("Manually triggering webhook: type={}, entityId={}", eventType, entityId);

        if (eventType.startsWith("payment.")) {
            MockPayment payment = paymentRepository.findByPaymentId(entityId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + entityId));

            switch (eventType) {
                case "payment.captured" -> dispatchPaymentCaptured(payment);
                case "payment.authorized" -> dispatchPaymentAuthorized(payment);
                case "payment.failed" -> dispatchPaymentFailed(payment);
                default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
            }
        } else if (eventType.startsWith("refund.")) {
            MockRefund refund = refundRepository.findByRefundId(entityId)
                .orElseThrow(() -> new IllegalArgumentException("Refund not found: " + entityId));
            MockPayment payment = paymentRepository.findByPaymentId(refund.getPaymentId())
                .orElseThrow(() -> new IllegalArgumentException("Payment not found for refund"));

            switch (eventType) {
                case "refund.processed" -> dispatchRefundProcessed(refund, payment);
                case "refund.failed" -> dispatchRefundFailed(refund, payment);
                default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
            }
        } else {
            throw new IllegalArgumentException("Unknown event type prefix: " + eventType);
        }
    }

    @Override
    @Async("webhookExecutor")
    public void dispatchPaymentCapturedWebhook(MockPayment payment, long delayMs) {
        if (SagaOrigin.isSaga(payment.getNotes())) return;
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            dispatchPaymentCaptured(payment);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Webhook dispatch interrupted");
        }
    }

    @Override
    @Async("webhookExecutor")
    public void dispatchPaymentFailedWebhook(MockPayment payment, long delayMs) {
        if (SagaOrigin.isSaga(payment.getNotes())) return;
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            dispatchPaymentFailed(payment);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Webhook dispatch interrupted");
        }
    }

    @Override
    @Async("webhookExecutor")
    public void dispatchRefundProcessedWebhook(MockRefund refund, long delayMs) {
        if (SagaOrigin.isSaga(refund.getNotes())) return;
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            MockPayment payment = paymentRepository.findByPaymentId(refund.getPaymentId())
                .orElse(null);
            if (payment != null) {
                dispatchRefundProcessed(refund, payment);
            } else {
                log.warn("Payment not found for refund webhook: {}", refund.getRefundId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Webhook dispatch interrupted");
        }
    }

    private Map<String, Object> buildPaymentWebhookPayload(String eventType, MockPayment payment) {
        Map<String, Object> paymentEntity = new HashMap<>();
        paymentEntity.put("id", payment.getPaymentId());
        paymentEntity.put("entity", "payment");
        paymentEntity.put("amount", payment.getAmount());
        paymentEntity.put("currency", payment.getCurrency());
        paymentEntity.put("status", payment.getStatus());
        paymentEntity.put("order_id", payment.getOrderId());
        paymentEntity.put("method", payment.getMethod());
        paymentEntity.put("description", payment.getDescription());
        paymentEntity.put("email", payment.getEmail());
        paymentEntity.put("contact", payment.getContact());
        paymentEntity.put("fee", payment.getFee());
        paymentEntity.put("tax", payment.getTax());
        paymentEntity.put("captured", payment.getCaptured());
        paymentEntity.put("notes", payment.getNotes());
        paymentEntity.put("created_at", payment.getCreatedAt());

        if ("wallet".equals(payment.getMethod())) {
            paymentEntity.put("wallet", payment.getWallet());
        } else if ("upi".equals(payment.getMethod())) {
            paymentEntity.put("vpa", payment.getVpa());
        } else if ("card".equals(payment.getMethod())) {
            Map<String, Object> card = new HashMap<>();
            card.put("id", payment.getCardId());
            card.put("last4", payment.getCardLast4());
            card.put("network", payment.getCardNetwork());
            card.put("type", payment.getCardType());
            paymentEntity.put("card", card);
        } else if ("netbanking".equals(payment.getMethod())) {
            paymentEntity.put("bank", payment.getBank());
        }

        if ("failed".equals(payment.getStatus())) {
            paymentEntity.put("error_code", payment.getErrorCode());
            paymentEntity.put("error_description", payment.getErrorDescription());
            paymentEntity.put("error_source", payment.getErrorSource());
            paymentEntity.put("error_step", payment.getErrorStep());
            paymentEntity.put("error_reason", payment.getErrorReason());
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("entity", "event");
        payload.put("event", eventType);
        payload.put("account_id", "acc_mock_FitPro");
        payload.put("contains", List.of("payment"));
        payload.put("payload", Map.of("payment", Map.of("entity", paymentEntity)));
        payload.put("created_at", Instant.now().getEpochSecond());

        return payload;
    }

    private Map<String, Object> buildRefundWebhookPayload(String eventType, MockRefund refund, MockPayment payment) {
        Map<String, Object> refundEntity = new HashMap<>();
        refundEntity.put("id", refund.getRefundId());
        refundEntity.put("entity", "refund");
        refundEntity.put("amount", refund.getAmount());
        refundEntity.put("currency", refund.getCurrency());
        refundEntity.put("payment_id", refund.getPaymentId());
        refundEntity.put("receipt", refund.getReceipt());
        refundEntity.put("status", refund.getStatus());
        refundEntity.put("speed_requested", refund.getSpeedRequested());
        refundEntity.put("speed_processed", refund.getSpeedProcessed());
        refundEntity.put("notes", refund.getNotes());
        refundEntity.put("acquirer_data", refund.getAcquirerData());
        refundEntity.put("created_at", refund.getCreatedAt());

        Map<String, Object> paymentEntity = new HashMap<>();
        if (payment != null) {
            paymentEntity.put("id", payment.getPaymentId());
            paymentEntity.put("entity", "payment");
            paymentEntity.put("amount", payment.getAmount());
            paymentEntity.put("currency", payment.getCurrency());
            paymentEntity.put("status", payment.getStatus());
            paymentEntity.put("order_id", payment.getOrderId());
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("entity", "event");
        payload.put("event", eventType);
        payload.put("account_id", "acc_mock_FitPro");
        payload.put("contains", List.of("refund", "payment"));
        payload.put("payload", Map.of(
            "refund", Map.of("entity", refundEntity),
            "payment", Map.of("entity", paymentEntity)
        ));
        payload.put("created_at", Instant.now().getEpochSecond());

        return payload;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void dispatchWebhook(Map<String, Object> payload, String referenceId) {
        // Env var (via properties) takes priority over database config — critical for Docker deployments
        // where the database seed has localhost URLs but the env var has Docker service names
        String webhookUrl = (properties.getWebhookUrl() != null && !properties.getWebhookUrl().isBlank())
                ? properties.getWebhookUrl()
                : configService.getWebhookUrl().orElse("http://localhost:8081/api/customer/api/v1/webhooks/payment");
        String eventId = idGenerator.generateEventId();
        String eventType = (String) payload.get("event");

        MockWebhook webhook = new MockWebhook();
        webhook.setEventId(eventId);
        webhook.setEventType(eventType);
        webhook.setPayload(payload);
        webhook.setWebhookUrl(webhookUrl);
        webhook.setStatus("pending");
        webhook.setAttempts(0);
        webhook.setMaxAttempts(MAX_RETRY_ATTEMPTS);
        webhook.setCreatedAt(Instant.now());

        webhook = webhookRepository.save(webhook);

        sleep(randomDelay());

        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            sendWebhookRequest(webhook, payloadJson);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize webhook payload: {}", eventId, e);
            webhook.recordFailedAttempt(500, "Serialization error: " + e.getMessage());
            webhookRepository.save(webhook);
        }
    }

    private void sendWebhookRequest(MockWebhook webhook, String payloadJson) {
        String signature = signatureUtil.generateSignature(payloadJson);

        try {
            restClient.post()
                .uri(webhook.getWebhookUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", signature)
                .header("X-Mock-Signature", signature)
                .header("X-Razorpay-Event-Id", webhook.getEventId())
                .header("User-Agent", "Mock-Razorpay-Webhook/1.0")
                .body(payloadJson)
                .retrieve()
                .toBodilessEntity();

            webhook.recordSuccessfulDelivery(200, "OK");
            log.info("Webhook delivered successfully: {} -> {}", webhook.getEventId(), webhook.getWebhookUrl());

        } catch (RestClientException e) {
            log.error("Webhook delivery error for {}: {}", webhook.getEventId(), e.getMessage());
            webhook.recordFailedAttempt(0, "Connection error: " + e.getMessage());
            scheduleRetry(webhook);
        }

        webhookRepository.save(webhook);
    }

    private void scheduleRetry(MockWebhook webhook) {
        if (webhook.getAttempts() < MAX_RETRY_ATTEMPTS) {
            int delayIndex = Math.min(webhook.getAttempts(), RETRY_DELAYS_SECONDS.length - 1);
            int delaySeconds = RETRY_DELAYS_SECONDS[delayIndex];
            Instant nextRetry = Instant.now().plus(delaySeconds, ChronoUnit.SECONDS);
            webhook.setNextRetryAt(nextRetry);
            webhook.setStatus("pending");
            log.info("Scheduled retry for webhook {} at {} (attempt {})",
                     webhook.getEventId(), nextRetry, webhook.getAttempts() + 1);
        } else {
            webhook.setStatus("failed");
            log.warn("Webhook {} exceeded max retry attempts, marking as failed", webhook.getEventId());
        }
    }

    private long calculateDelay(SimulatedOutcome outcome) {
        return switch (outcome) {
            case DELAYED_WEBHOOK -> 30_000L;
            case LATE_WEBHOOK -> 2_100_000L;  // 35 minutes — arrives after intent expiry window
            default -> 0L;
        };
    }

    private long randomDelay() {
        return WEBHOOK_DELAY_MIN_MS + (long) (Math.random() * (WEBHOOK_DELAY_MAX_MS - WEBHOOK_DELAY_MIN_MS));
    }

    private void sleep(long millis) {
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Webhook delay interrupted");
            }
        }
    }
}

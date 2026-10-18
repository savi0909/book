package com.aaztra.mockpg.scheduler;

import com.aaztra.mockpg.entity.MockWebhook;
import com.aaztra.mockpg.repository.MockWebhookRepository;
import com.aaztra.mockpg.service.MockWebhookDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Scheduler for retrying failed webhook deliveries.
 * Runs every minute to check for webhooks ready for retry.
 */
@Component
public class MockWebhookRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(MockWebhookRetryScheduler.class);

    private final MockWebhookRepository webhookRepository;
    private final MockWebhookDispatcher webhookDispatcher;

    public MockWebhookRetryScheduler(
            MockWebhookRepository webhookRepository,
            MockWebhookDispatcher webhookDispatcher) {
        this.webhookRepository = webhookRepository;
        this.webhookDispatcher = webhookDispatcher;
    }

    /**
     * Find and retry webhooks that are ready for retry.
     * Runs every 60 seconds.
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void retryFailedWebhooks() {
        Instant now = Instant.now();

        List<MockWebhook> pendingWebhooks = webhookRepository
            .findByStatusAndNextRetryAtBefore("pending", now);

        if (pendingWebhooks.isEmpty()) {
            log.debug("No webhooks ready for retry");
            return;
        }

        log.info("Found {} webhooks ready for retry", pendingWebhooks.size());

        for (MockWebhook webhook : pendingWebhooks) {
            try {
                webhookDispatcher.retryWebhook(webhook);
            } catch (Exception e) {
                log.error("Error retrying webhook: {}", webhook.getEventId(), e);
            }
        }
    }

    /**
     * Clean up old webhook records (older than 7 days).
     * Runs daily at 2 AM.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupOldWebhooks() {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);

        int deleted = webhookRepository.deleteByCreatedAtBefore(cutoff);

        if (deleted > 0) {
            log.info("Cleaned up {} old webhook records", deleted);
        }
    }
}

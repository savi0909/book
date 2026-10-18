package com.aaztra.mockpg.controller;

import com.aaztra.mockpg.dto.*;
import com.aaztra.mockpg.entity.MockWallet;
import com.aaztra.mockpg.entity.MockWebhook;
import com.aaztra.mockpg.repository.MockWalletRepository;
import com.aaztra.mockpg.repository.MockWebhookRepository;
import com.aaztra.mockpg.service.MockConfigService;
import com.aaztra.mockpg.service.MockWebhookDispatcher;
import com.aaztra.mockpg.service.MockWalletService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Admin controller for Mock Payment Processor utilities.
 *
 * Provides endpoints for:
 * - Configuration management
 * - Test wallet management
 * - Webhook monitoring and manual triggering
 *
 * NOTE: In production, these endpoints should be protected with admin authentication.
 * For Sprint 4.1C, they are accessible for testing purposes.
 */
@RestController
@RequestMapping("/mock-pg/admin")
public class MockAdminController {

    private static final Logger log = LoggerFactory.getLogger(MockAdminController.class);

    private final MockConfigService configService;
    private final MockWalletService walletService;
    private final MockWalletRepository walletRepository;
    private final MockWebhookDispatcher webhookDispatcher;
    private final MockWebhookRepository webhookRepository;

    public MockAdminController(
            MockConfigService configService,
            MockWalletService walletService,
            MockWalletRepository walletRepository,
            MockWebhookDispatcher webhookDispatcher,
            MockWebhookRepository webhookRepository) {
        this.configService = configService;
        this.walletService = walletService;
        this.walletRepository = walletRepository;
        this.webhookDispatcher = webhookDispatcher;
        this.webhookRepository = webhookRepository;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get all configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<MockConfigResponse> getConfig() {
        Map<String, Object> config = configService.getAllConfig();
        return ResponseEntity.ok(MockConfigResponse.of(config));
    }

    /**
     * Update configuration.
     */
    @PutMapping("/config")
    public ResponseEntity<MockConfigResponse> updateConfig(
            @Valid @RequestBody UpdateMockConfigRequest request) {
        log.info("Updating config: {} = {}", request.key(), request.value());

        configService.updateConfig(request.key(), request.value());

        Map<String, Object> config = configService.getAllConfig();
        return ResponseEntity.ok(MockConfigResponse.of(config, "Configuration updated"));
    }

    /**
     * Reset configuration to defaults.
     */
    @PostMapping("/config/reset")
    public ResponseEntity<MockConfigResponse> resetConfig() {
        log.info("Resetting configuration to defaults");

        configService.resetToDefaults();

        Map<String, Object> config = configService.getAllConfig();
        return ResponseEntity.ok(MockConfigResponse.of(config, "Configuration reset to defaults"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WALLET MANAGEMENT ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Create a test wallet.
     */
    @PostMapping("/wallets")
    public ResponseEntity<MockWalletResponse> createWallet(
            @Valid @RequestBody CreateTestWalletRequest request) {
        log.info("Creating test wallet: {}", request.walletId());

        MockWallet wallet = walletService.createWallet(
            request.walletId(),
            request.balance(),
            request.status()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(MockWalletResponse.from(wallet));
    }

    /**
     * Get wallet by ID.
     */
    @GetMapping("/wallets/{walletId}")
    public ResponseEntity<MockWalletResponse> getWallet(@PathVariable String walletId) {
        MockWallet wallet = walletService.getOrCreateWallet(walletId);
        return ResponseEntity.ok(MockWalletResponse.from(wallet));
    }

    /**
     * Update wallet balance.
     */
    @PatchMapping("/wallets/{walletId}/balance")
    public ResponseEntity<MockWalletResponse> updateWalletBalance(
            @PathVariable String walletId,
            @Valid @RequestBody UpdateWalletBalanceRequest request) {
        log.info("Updating wallet {} balance to {}", walletId, request.balance());

        MockWallet wallet = walletService.updateBalance(walletId, request.balance());

        return ResponseEntity.ok(MockWalletResponse.from(wallet));
    }

    /**
     * Update wallet status (active/blocked).
     */
    @PatchMapping("/wallets/{walletId}/status")
    public ResponseEntity<MockWalletResponse> updateWalletStatus(
            @PathVariable String walletId,
            @RequestParam String status) {
        log.info("Updating wallet {} status to {}", walletId, status);

        MockWallet wallet = walletService.updateStatus(walletId, status);

        return ResponseEntity.ok(MockWalletResponse.from(wallet));
    }

    /**
     * List all wallets.
     */
    @GetMapping("/wallets")
    public ResponseEntity<List<MockWalletResponse>> listWallets() {
        List<MockWalletResponse> wallets = walletRepository.findAll().stream()
            .map(MockWalletResponse::from)
            .toList();
        return ResponseEntity.ok(wallets);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WEBHOOK MANAGEMENT ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * List webhooks with optional status filter.
     */
    @GetMapping("/webhooks")
    public ResponseEntity<WebhookListResponse> listWebhooks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {

        List<MockWebhook> webhooks;
        if (status != null) {
            webhooks = webhookRepository.findByStatus(status,
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")));
        } else {
            webhooks = webhookRepository.findAll(
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
        }

        List<WebhookListResponse.WebhookSummary> summaries = webhooks.stream()
            .map(w -> new WebhookListResponse.WebhookSummary(
                w.getEventId(),
                w.getEventType(),
                w.getStatus(),
                w.getAttempts(),
                w.getCreatedAt().toString(),
                w.getDeliveredAt() != null ? w.getDeliveredAt().toString() : null
            ))
            .toList();

        // Get counts
        int pending = (int) webhookRepository.countByStatus("pending");
        int delivered = (int) webhookRepository.countByStatus("delivered");
        int failed = (int) webhookRepository.countByStatus("failed");

        return ResponseEntity.ok(new WebhookListResponse(
            summaries, summaries.size(), pending, delivered, failed
        ));
    }

    /**
     * Get webhook details.
     */
    @GetMapping("/webhooks/{eventId}")
    public ResponseEntity<WebhookDetailResponse> getWebhook(@PathVariable String eventId) {
        MockWebhook webhook = webhookRepository.findByEventId(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Webhook not found: " + eventId));
        return ResponseEntity.ok(WebhookDetailResponse.from(webhook));
    }

    /**
     * Retry a failed webhook manually.
     */
    @PostMapping("/webhooks/{eventId}/retry")
    public ResponseEntity<Map<String, String>> retryWebhook(@PathVariable String eventId) {
        log.info("Manually retrying webhook: {}", eventId);

        MockWebhook webhook = webhookRepository.findByEventId(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Webhook not found: " + eventId));

        if ("delivered".equals(webhook.getStatus())) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Webhook already delivered"));
        }

        // Reset for retry
        webhook.setNextRetryAt(Instant.now());
        webhookRepository.save(webhook);

        webhookDispatcher.retryWebhook(webhook);

        return ResponseEntity.ok(Map.of(
            "message", "Webhook retry triggered",
            "eventId", eventId
        ));
    }

    /**
     * Trigger a webhook manually for testing.
     */
    @PostMapping("/webhooks/trigger")
    public ResponseEntity<Map<String, String>> triggerWebhook(
            @Valid @RequestBody TriggerWebhookRequest request) {
        log.info("Manually triggering webhook: {} for {}", request.eventType(), request.entityId());

        webhookDispatcher.triggerWebhook(request.eventType(), request.entityId());

        return ResponseEntity.ok(Map.of(
            "message", "Webhook triggered",
            "eventType", request.eventType(),
            "entityId", request.entityId()
        ));
    }

    /**
     * Delete all webhooks (cleanup utility).
     */
    @DeleteMapping("/webhooks")
    public ResponseEntity<Map<String, Object>> deleteAllWebhooks() {
        log.warn("Deleting all webhooks");

        long count = webhookRepository.count();
        webhookRepository.deleteAll();

        return ResponseEntity.ok(Map.of(
            "message", "All webhooks deleted",
            "count", count
        ));
    }
}

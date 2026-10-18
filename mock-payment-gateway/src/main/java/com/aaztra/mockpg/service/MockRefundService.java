package com.aaztra.mockpg.service;

import com.aaztra.mockpg.dto.CreateMockRefundRequest;
import com.aaztra.mockpg.dto.MockRefundResponse;
import com.aaztra.mockpg.entity.MockPayment;
import com.aaztra.mockpg.entity.MockRefund;
import com.aaztra.mockpg.entity.MockWallet;
import com.aaztra.mockpg.entity.MockWalletTransaction;
import com.aaztra.mockpg.exception.*;
import com.aaztra.mockpg.repository.*;
import com.aaztra.mockpg.util.MagicAmountResolver;
import com.aaztra.mockpg.util.MagicAmountResolver.SimulatedOutcome;
import com.aaztra.mockpg.util.MockIdGenerator;
import com.aaztra.mockpg.saga.SagaOrigin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class MockRefundService {

    private static final Logger log = LoggerFactory.getLogger(MockRefundService.class);

    private final MockRefundRepository refundRepository;
    private final MockPaymentRepository paymentRepository;
    private final MockWalletRepository walletRepository;
    private final MockWalletTransactionRepository transactionRepository;
    private final MockIdGenerator idGenerator;
    private final MagicAmountResolver magicAmountResolver;
    private final MockWebhookDispatcher webhookDispatcher;

    public MockRefundService(MockRefundRepository refundRepository,
                             MockPaymentRepository paymentRepository,
                             MockWalletRepository walletRepository,
                             MockWalletTransactionRepository transactionRepository,
                             MockIdGenerator idGenerator,
                             MagicAmountResolver magicAmountResolver,
                             MockWebhookDispatcher webhookDispatcher) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.idGenerator = idGenerator;
        this.magicAmountResolver = magicAmountResolver;
        this.webhookDispatcher = webhookDispatcher;
    }

    /**
     * Create a refund for a payment.
     */
    public MockRefundResponse createRefund(String paymentId, CreateMockRefundRequest request) {
        return createRefund(paymentId, request, false);
    }

    /** Internal adapter entry; the legacy HTTP endpoint cannot mutate saga records. */
    public MockRefundResponse createSagaRefund(String paymentId, CreateMockRefundRequest request) {
        return createRefund(paymentId, request, true);
    }

    private MockRefundResponse createRefund(String paymentId, CreateMockRefundRequest request, boolean sagaAdapter) {
        log.info("Creating refund for payment: {}, amount: {}", paymentId, request.amount());

        // Get and validate payment
        MockPayment payment = paymentRepository.findByPaymentId(paymentId)
            .orElseThrow(() -> new MockPaymentNotFoundException(paymentId));

        if (SagaOrigin.isSaga(payment.getNotes()) && !sagaAdapter) {
            throw new MockPaymentException("BAD_REQUEST_ERROR", "Saga records require the saga operation API",
                "business", "saga_operation_required");
        }

        if (!"captured".equals(payment.getStatus())) {
            throw new InvalidPaymentStateException(paymentId, payment.getStatus(), "captured");
        }

        // Check refundable amount
        Long refundableAmount = payment.getAmount() - payment.getAmountRefunded();
        if (request.amount() > refundableAmount) {
            throw new RefundExceedsPaymentException(request.amount(), refundableAmount);
        }

        // Create refund
        MockRefund refund = new MockRefund();
        refund.setRefundId(idGenerator.generateRefundId());
        refund.setPaymentId(paymentId);
        refund.setAmount(request.amount());
        refund.setCurrency(payment.getCurrency());
        refund.setReceipt(request.receipt());
        refund.setSpeedRequested(request.speed());
        refund.setNotes(request.notes());
        refund.setCreatedAt(Instant.now().getEpochSecond());
        refund.setUpdatedAt(Instant.now());

        // Check magic amounts for refund outcome
        SimulatedOutcome outcome = magicAmountResolver.resolveRefundOutcome(request.amount());

        if (outcome == SimulatedOutcome.REFUND_FAILED) {
            refund.setStatus("failed");
            refund.setSpeedProcessed(null);
        } else {
            refund.setStatus("processed");
            refund.setSpeedProcessed(request.speed());
            refund.setProcessedAt(Instant.now().getEpochSecond());

            // Generate acquirer data (bank reference)
            Map<String, String> acquirerData = new HashMap<>();
            acquirerData.put("rrn", UUID.randomUUID().toString().substring(0, 12).toUpperCase());
            acquirerData.put("arn", "MOCK" + System.currentTimeMillis());
            refund.setAcquirerData(acquirerData);

            // Update payment refund tracking
            payment.setAmountRefunded(payment.getAmountRefunded() + request.amount());

            if (payment.getAmountRefunded().equals(payment.getAmount())) {
                payment.setRefundStatus("full");
            } else {
                payment.setRefundStatus("partial");
            }
            paymentRepository.save(payment);

            // Credit wallet if original payment was wallet
            if ("wallet".equals(payment.getMethod()) && payment.getVpa() != null) {
                creditWallet(payment.getVpa(), request.amount(), refund.getRefundId());
            }
        }

        MockRefund saved = refundRepository.save(refund);
        log.info("Created refund: {} with status: {}", saved.getRefundId(), saved.getStatus());

        // Dispatch webhook
        if (SagaOrigin.isSaga(saved.getNotes())) {
            return MockRefundResponse.from(saved);
        }
        if (saved.isProcessed()) {
            webhookDispatcher.dispatchRefundProcessed(saved, payment);
        } else if ("failed".equals(saved.getStatus())) {
            webhookDispatcher.dispatchRefundFailed(saved, payment);
        }

        return MockRefundResponse.from(saved);
    }

    private void creditWallet(String walletId, Long amount, String refundId) {
        MockWallet wallet = walletRepository.findByWalletId(walletId)
            .orElse(null);

        if (wallet != null) {
            wallet.credit(amount);
            walletRepository.save(wallet);

            // Record transaction
            MockWalletTransaction txn = new MockWalletTransaction();
            txn.setWalletId(walletId);
            txn.setTransactionType("refund_credit");
            txn.setAmount(amount);
            txn.setBalanceAfter(wallet.getBalance());
            txn.setReferenceType("refund");
            txn.setReferenceId(refundId);
            txn.setDescription("Refund credit for " + refundId);
            txn.setCreatedAt(Instant.now());
            transactionRepository.save(txn);

            log.info("Credited wallet {}: amount={}, newBalance={}", walletId, amount, wallet.getBalance());
        }
    }

    /**
     * Get refund by gateway refund ID.
     */
    @Transactional(readOnly = true)
    public MockRefundResponse getRefund(String refundId) {
        MockRefund refund = refundRepository.findByRefundId(refundId)
            .orElseThrow(() -> new MockRefundNotFoundException(refundId));
        return MockRefundResponse.from(refund);
    }

    /**
     * Get all refunds for a payment.
     */
    @Transactional(readOnly = true)
    public List<MockRefundResponse> getRefundsByPaymentId(String paymentId) {
        return refundRepository.findByPaymentId(paymentId).stream()
            .map(MockRefundResponse::from)
            .collect(Collectors.toList());
    }
}

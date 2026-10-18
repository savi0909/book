package com.aaztra.mockpg.service;

import com.aaztra.mockpg.dto.MockPaymentResponse;
import com.aaztra.mockpg.dto.SimulatePaymentRequest;
import com.aaztra.mockpg.entity.MockOrder;
import com.aaztra.mockpg.entity.MockPayment;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class MockPaymentService {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentService.class);

    private final MockPaymentRepository paymentRepository;
    private final MockOrderRepository orderRepository;
    private final MockOrderService orderService;
    private final MockWalletRepository walletRepository;
    private final MockWalletTransactionRepository transactionRepository;
    private final MockIdGenerator idGenerator;
    private final MagicAmountResolver magicAmountResolver;
    private final MockWebhookDispatcher webhookDispatcher;

    private final BigDecimal feePercentage = new BigDecimal("2.0");
    private final BigDecimal gstPercentage = new BigDecimal("18.0");
    private boolean autoCapture = true;

    public MockPaymentService(MockPaymentRepository paymentRepository,
                              MockOrderRepository orderRepository,
                              MockOrderService orderService,
                              MockWalletRepository walletRepository,
                              MockWalletTransactionRepository transactionRepository,
                              MockIdGenerator idGenerator,
                              MagicAmountResolver magicAmountResolver,
                              MockWebhookDispatcher webhookDispatcher) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.idGenerator = idGenerator;
        this.magicAmountResolver = magicAmountResolver;
        this.webhookDispatcher = webhookDispatcher;
    }

    /**
     * Simulate a payment for an order.
     * This is a TEST endpoint - real Razorpay uses SDK checkout.
     */
    public MockPaymentResponse simulatePayment(String orderId, SimulatePaymentRequest request) {
        return simulatePayment(orderId, request, false);
    }

    /** Internal adapter entry; the legacy HTTP endpoint cannot mutate saga records. */
    public MockPaymentResponse simulateSagaPayment(String orderId, SimulatePaymentRequest request) {
        return simulatePayment(orderId, request, true);
    }

    private MockPaymentResponse simulatePayment(String orderId, SimulatePaymentRequest request, boolean sagaAdapter) {
        log.info("Simulating payment for order: {}, method: {}", orderId, request.method());

        // Validate request
        request.validate();

        // Get and validate order
        MockOrder order = orderRepository.findByOrderId(orderId)
            .orElseThrow(() -> new MockOrderNotFoundException(orderId));

        if (SagaOrigin.isSaga(order.getNotes()) && !sagaAdapter) {
            throw new MockPaymentException("BAD_REQUEST_ERROR", "Saga records require the saga operation API",
                "business", "saga_operation_required");
        }

        if (!order.canAcceptPayment()) {
            throw new OrderAlreadyPaidException(orderId);
        }

        // Record payment attempt
        orderService.recordPaymentAttempt(orderId);

        // Create payment record
        MockPayment payment = new MockPayment();
        payment.setPaymentId(idGenerator.generatePaymentId());
        payment.setOrderId(orderId);
        payment.setAmount(order.getAmountDue());
        payment.setCurrency(order.getCurrency());
        payment.setMethod(request.method());
        payment.setDescription(request.description());
        payment.setEmail(request.email());
        payment.setContact(request.contact());
        payment.setNotes(request.notes() != null ? request.notes() : order.getNotes());
        payment.setCreatedAt(Instant.now().getEpochSecond());
        payment.setUpdatedAt(Instant.now());

        // Set method-specific fields
        populateMethodSpecificFields(payment, request);

        // Check magic amounts for simulated outcomes
        SimulatedOutcome outcome = magicAmountResolver.resolvePaymentOutcome(payment.getAmount());

        if (outcome == SimulatedOutcome.SUCCESS || outcome == SimulatedOutcome.AUTHORIZATION_ONLY
            || outcome == SimulatedOutcome.DELAYED_WEBHOOK || outcome == SimulatedOutcome.DUPLICATE_WEBHOOK
            || outcome == SimulatedOutcome.CONCURRENT_CAPTURE || outcome == SimulatedOutcome.LATE_WEBHOOK
            || outcome == SimulatedOutcome.PARTIAL_CAPTURE) {
            processSuccessfulPayment(payment, order, request, outcome);
        } else {
            processFailedPayment(payment, outcome);
        }

        MockPayment saved = paymentRepository.save(payment);
        log.info("Created payment: {} with status: {}", saved.getPaymentId(), saved.getStatus());

        // Schedule webhook dispatch
        if (SagaOrigin.isSaga(saved.getNotes())) {
            return MockPaymentResponse.from(saved);
        }
        if (saved.isSuccessful()) {
            if ("authorized".equals(saved.getStatus())) {
                webhookDispatcher.dispatchPaymentAuthorized(saved);
            } else {
                webhookDispatcher.dispatchPaymentCaptured(saved);
            }
        } else {
            webhookDispatcher.dispatchPaymentFailed(saved);
        }

        return MockPaymentResponse.from(saved);
    }

    private void populateMethodSpecificFields(MockPayment payment, SimulatePaymentRequest request) {
        switch (request.method()) {
            case "wallet" -> {
                payment.setWallet("mock_wallet");
                payment.setVpa(request.walletId());
            }
            case "upi" -> {
                payment.setVpa(request.vpa());
            }
            case "card" -> {
                payment.setCardId("card_mock_" + idGenerator.generatePaymentId().substring(9));
                payment.setCardLast4(request.cardNumber().substring(12));
                payment.setCardNetwork(detectCardNetwork(request.cardNumber()));
                payment.setCardType("credit");
            }
            case "netbanking" -> {
                payment.setBank(request.bankCode());
            }
        }
    }

    private String detectCardNetwork(String cardNumber) {
        if (cardNumber.startsWith("4")) return "Visa";
        if (cardNumber.startsWith("5")) return "MasterCard";
        if (cardNumber.startsWith("37")) return "Amex";
        if (cardNumber.startsWith("6")) return "RuPay";
        return "Unknown";
    }

    private void processSuccessfulPayment(MockPayment payment, MockOrder order,
                                          SimulatePaymentRequest request, SimulatedOutcome outcome) {
        // For wallet payments, check and debit balance
        if ("wallet".equals(request.method())) {
            MockWallet wallet = walletRepository.findByWalletId(request.walletId())
                .orElseGet(() -> createDefaultWallet(request.walletId()));

            if (!wallet.canDebit(payment.getAmount())) {
                throw new InsufficientBalanceException(request.walletId());
            }

            // Debit wallet
            wallet.debit(payment.getAmount());
            walletRepository.save(wallet);

            // Record transaction
            MockWalletTransaction txn = new MockWalletTransaction();
            txn.setWalletId(request.walletId());
            txn.setTransactionType("debit");
            txn.setAmount(payment.getAmount());
            txn.setBalanceAfter(wallet.getBalance());
            txn.setReferenceType("payment");
            txn.setReferenceId(payment.getPaymentId());
            txn.setDescription("Payment debit for " + payment.getPaymentId());
            txn.setCreatedAt(Instant.now());
            transactionRepository.save(txn);
        }

        // Calculate fees
        calculateFees(payment);

        // Determine capture behavior
        boolean shouldCapture = autoCapture && outcome != SimulatedOutcome.AUTHORIZATION_ONLY
            && !(SagaOrigin.isSaga(payment.getNotes()) && outcome == SimulatedOutcome.PARTIAL_CAPTURE);

        if (shouldCapture) {
            // Partial capture: capture only 80% of authorized amount (Razorpay/Visa partial capture)
            if (outcome == SimulatedOutcome.PARTIAL_CAPTURE) {
                long partialAmount = (long) (payment.getAmount() * 0.8);
                log.info("MAGIC_AMOUNT_PARTIAL: Capturing {}→{} (80%) for order {}",
                        payment.getAmount(), partialAmount, order.getOrderId());
                payment.setAmount(partialAmount);
                calculateFees(payment);
            }

            payment.setStatus("captured");
            payment.setCaptured(true);
            payment.setCapturedAt(Instant.now().getEpochSecond());

            // Mark order as paid
            orderService.markOrderAsPaid(order.getOrderId(), payment.getAmount());
        } else {
            payment.setStatus("authorized");
            payment.setCaptured(false);
        }
    }

    private MockWallet createDefaultWallet(String walletId) {
        log.info("Creating default wallet for: {}", walletId);
        MockWallet wallet = new MockWallet();
        wallet.setWalletId(walletId);
        wallet.setProvider("mock_wallet");
        wallet.setBalance(100_000_00L);  // Default balance
        wallet.setStatus("active");
        wallet.setCreatedAt(Instant.now());
        wallet.setUpdatedAt(Instant.now());
        return walletRepository.save(wallet);
    }

    private void processFailedPayment(MockPayment payment, SimulatedOutcome outcome) {
        payment.setStatus("failed");
        payment.setCaptured(false);

        MagicAmountResolver.ErrorDetails errorDetails = magicAmountResolver.getErrorDetails(outcome);
        if (errorDetails != null) {
            payment.setErrorCode(errorDetails.errorCode());
            payment.setErrorDescription(errorDetails.errorDescription());
            payment.setErrorSource(errorDetails.errorSource());
            payment.setErrorStep(errorDetails.errorStep());
            payment.setErrorReason(errorDetails.errorReason());
        }
    }

    private void calculateFees(MockPayment payment) {
        BigDecimal amount = BigDecimal.valueOf(payment.getAmount());
        BigDecimal fee = amount.multiply(feePercentage).divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING);
        BigDecimal tax = fee.multiply(gstPercentage).divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING);

        payment.setFee(fee.longValue());
        payment.setTax(tax.longValue());
    }

    /**
     * Get payment by gateway payment ID.
     */
    @Transactional(readOnly = true)
    public MockPaymentResponse getPayment(String paymentId) {
        MockPayment payment = paymentRepository.findByPaymentId(paymentId)
            .orElseThrow(() -> new MockPaymentNotFoundException(paymentId));
        return MockPaymentResponse.from(payment);
    }

    /**
     * Get all payments for an order.
     */
    @Transactional(readOnly = true)
    public List<MockPaymentResponse> getPaymentsByOrderId(String orderId) {
        return paymentRepository.findByOrderId(orderId).stream()
            .map(MockPaymentResponse::from)
            .collect(Collectors.toList());
    }

    /**
     * Capture an authorized payment.
     */
    public MockPaymentResponse capturePayment(String paymentId, Long amount) {
        log.info("Capturing payment: {}, amount: {}", paymentId, amount);

        MockPayment payment = paymentRepository.findByPaymentId(paymentId)
            .orElseThrow(() -> new MockPaymentNotFoundException(paymentId));

        if (SagaOrigin.isSaga(payment.getNotes())) {
            throw new MockPaymentException("BAD_REQUEST_ERROR", "Saga capture outcome is immutable",
                "business", "saga_operation_required");
        }

        if (payment.getCaptured()) {
            throw new PaymentAlreadyCapturedException(paymentId);
        }

        if (!"authorized".equals(payment.getStatus())) {
            throw new InvalidPaymentStateException(paymentId, payment.getStatus(), "authorized");
        }

        if (amount > payment.getAmount()) {
            throw new MockPaymentException("BAD_REQUEST_ERROR",
                "Capture amount cannot exceed payment amount",
                "business", "invalid_capture_amount");
        }

        payment.setStatus("captured");
        payment.setCaptured(true);
        payment.setCapturedAt(Instant.now().getEpochSecond());
        payment.setUpdatedAt(Instant.now());

        // Mark order as paid
        orderService.markOrderAsPaid(payment.getOrderId(), amount);

        MockPayment saved = paymentRepository.save(payment);
        log.info("Captured payment: {}", paymentId);

        // Dispatch webhook
        webhookDispatcher.dispatchPaymentCaptured(saved);

        return MockPaymentResponse.from(saved);
    }

    /**
     * Get refundable amount for a payment.
     */
    @Transactional(readOnly = true)
    public Long getRefundableAmount(String paymentId) {
        MockPayment payment = paymentRepository.findByPaymentId(paymentId)
            .orElseThrow(() -> new MockPaymentNotFoundException(paymentId));

        return payment.getAmount() - payment.getAmountRefunded();
    }
}

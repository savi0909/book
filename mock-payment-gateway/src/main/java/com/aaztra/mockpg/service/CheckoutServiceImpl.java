package com.aaztra.mockpg.service;

import com.aaztra.mockpg.dto.CheckoutPayRequest;
import com.aaztra.mockpg.dto.MockPaymentResponse;
import com.aaztra.mockpg.dto.SimulatePaymentRequest;
import com.aaztra.mockpg.entity.MockOrder;
import com.aaztra.mockpg.exception.MockOrderNotFoundException;
import com.aaztra.mockpg.repository.MockOrderRepository;
import com.aaztra.mockpg.util.MockSignatureUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@Transactional(readOnly = true)
public class CheckoutServiceImpl implements CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutServiceImpl.class);

    private final MockOrderRepository orderRepository;
    private final MockPaymentService paymentService;
    private final MockSignatureUtil signatureUtil;

    public CheckoutServiceImpl(
            MockOrderRepository orderRepository,
            MockPaymentService paymentService,
            MockSignatureUtil signatureUtil) {
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
        this.signatureUtil = signatureUtil;
    }

    @Override
    public MockOrder loadOrderForCheckout(String orderId) {
        return orderRepository.findByOrderId(orderId)
            .orElseThrow(() -> new MockOrderNotFoundException(orderId));
    }

    @Override
    @Transactional
    public String processPayment(CheckoutPayRequest request) {
        log.info("Processing checkout payment for order: {}, method: {}", request.orderId(), request.method());

        MockOrder order = orderRepository.findByOrderId(request.orderId())
            .orElseThrow(() -> new MockOrderNotFoundException(request.orderId()));

        // Build SimulatePaymentRequest — field order: method, walletId, vpa, cardNumber,
        // cvv, expiry, cardHolderName, bankCode, email, contact, description, notes
        SimulatePaymentRequest simulateRequest = new SimulatePaymentRequest(
            request.method(),
            resolveWalletId(request),
            request.vpa(),
            request.cardNumber() != null ? request.cardNumber().replaceAll("\\s", "") : null,
            request.cvv(),
            request.cardExpiry(),
            null, // cardHolderName
            request.bank(),
            request.email() != null ? request.email() : "customer@test.com",
            request.contact() != null ? request.contact() : "9876543210",
            null, // description
            null  // notes
        );

        MockPaymentResponse payment = paymentService.simulatePayment(request.orderId(), simulateRequest);

        // Build redirect URL with Razorpay-style params
        String redirectUrl = request.redirectUrl();
        if (redirectUrl == null || redirectUrl.isBlank()) {
            // Fallback to a success/failure result page if no redirect URL provided
            redirectUrl = "/checkout/result";
        }

        String signature = signatureUtil.generatePaymentSignature(
            payment.orderId(),
            payment.id()
        );

        String finalRedirectUrl = UriComponentsBuilder.fromUriString(redirectUrl)
            .queryParam("razorpay_payment_id", payment.id())
            .queryParam("razorpay_order_id", payment.orderId())
            .queryParam("razorpay_signature", signature)
            .queryParam("payment_status", payment.status())
            .build()
            .toUriString();

        log.info("Payment {} completed with status {}. Redirecting to: {}",
                 payment.id(), payment.status(), finalRedirectUrl);

        return finalRedirectUrl;
    }

    private String resolveWalletId(CheckoutPayRequest request) {
        // For wallet payments, use walletId if provided, otherwise use contact as wallet identifier
        if ("wallet".equals(request.method())) {
            return request.walletId() != null ? request.walletId() : request.contact();
        }
        return null;
    }

}

package com.aaztra.mockpg.dto;

/**
 * Form POST body from the checkout page when user submits payment.
 */
public record CheckoutPayRequest(
    String orderId,
    String callbackUrl,
    String redirectUrl,
    String method,
    String walletId,
    String vpa,
    String cardNumber,
    String cardExpiry,
    String cvv,
    String bank,
    String email,
    String contact
) {}

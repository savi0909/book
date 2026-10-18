package com.aaztra.mockpg.dto;

/**
 * Query params for GET /checkout — mirrors Razorpay checkout redirect params.
 */
public record CheckoutRequest(
    String orderId,
    String callbackUrl,
    String redirectUrl,
    String name,
    String email,
    String contact,
    String description
) {}

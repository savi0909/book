package com.aaztra.mockpg.service;

import com.aaztra.mockpg.dto.CheckoutPayRequest;
import com.aaztra.mockpg.entity.MockOrder;

public interface CheckoutService {

    /**
     * Load order by gateway order ID for checkout page display.
     */
    MockOrder loadOrderForCheckout(String orderId);

    /**
     * Process payment submission from the checkout form.
     * Returns the redirect URL with payment params appended.
     */
    String processPayment(CheckoutPayRequest request);
}

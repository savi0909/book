package com.aaztra.mockpg.controller;

import com.aaztra.mockpg.dto.CheckoutPayRequest;
import com.aaztra.mockpg.entity.MockOrder;
import com.aaztra.mockpg.service.CheckoutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * Razorpay-style hosted checkout page controller.
 *
 * Flow:
 *   1. FitPro creates a mock order via POST /mock-pg/v1/orders
 *   2. FitPro redirects browser to GET /checkout?order_id=...&redirect_url=...
 *   3. User selects payment method and submits form
 *   4. POST /checkout/pay simulates the payment
 *   5. Browser is redirected to redirect_url with payment params
 */
@Controller
@RequestMapping("/checkout")
public class CheckoutController {

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    /**
     * Display the checkout page for a given order.
     *
     * Query params (Razorpay-compatible):
     *   - order_id: Gateway order ID (required)
     *   - callback_url: Webhook URL (optional, overrides config)
     *   - redirect_url: Where to redirect after payment (required for full flow)
     *   - name: Prefill customer name
     *   - email: Prefill customer email
     *   - contact: Prefill customer phone
     *   - description: Payment description
     */
    @GetMapping
    public String showCheckout(
            @RequestParam("order_id") String orderId,
            @RequestParam(value = "callback_url", required = false) String callbackUrl,
            @RequestParam(value = "redirect_url", required = false) String redirectUrl,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "contact", required = false) String contact,
            @RequestParam(value = "description", required = false) String description,
            Model model) {

        log.info("Checkout page requested for order: {}", orderId);

        MockOrder order = checkoutService.loadOrderForCheckout(orderId);

        model.addAttribute("order", order);
        model.addAttribute("orderId", orderId);
        model.addAttribute("callbackUrl", callbackUrl != null ? callbackUrl : "");
        model.addAttribute("redirectUrl", redirectUrl != null ? redirectUrl : "");
        model.addAttribute("prefillName", name != null ? name : "");
        model.addAttribute("prefillEmail", email != null ? email : "");
        model.addAttribute("prefillContact", contact != null ? contact : "");
        model.addAttribute("description", description != null ? description : "Payment");
        model.addAttribute("amountInRupees", formatRupees(order.getAmount()));

        return "checkout";
    }

    /**
     * Process the payment form submission.
     * Simulates the payment and redirects to the redirect_url with payment params.
     */
    @PostMapping("/pay")
    public String processPayment(@ModelAttribute CheckoutPayRequest request) {
        log.info("Processing payment for order: {}, method: {}", request.orderId(), request.method());

        String redirectUrl = checkoutService.processPayment(request);
        return "redirect:" + redirectUrl;
    }

    /**
     * Standalone result page — shown when no redirect_url is configured.
     */
    @GetMapping("/result")
    public String showResult(
            @RequestParam(value = "razorpay_payment_id", required = false) String paymentId,
            @RequestParam(value = "razorpay_order_id", required = false) String orderId,
            @RequestParam(value = "razorpay_signature", required = false) String signature,
            @RequestParam(value = "payment_status", required = false) String status,
            Model model) {

        model.addAttribute("paymentId", paymentId);
        model.addAttribute("orderId", orderId);
        model.addAttribute("signature", signature);
        model.addAttribute("status", status);
        model.addAttribute("success", "captured".equals(status) || "authorized".equals(status));

        return "pay-result";
    }

    private String formatRupees(Long amountInPaise) {
        if (amountInPaise == null) return "0.00";
        return BigDecimal.valueOf(amountInPaise).movePointLeft(2).toPlainString();
    }
}

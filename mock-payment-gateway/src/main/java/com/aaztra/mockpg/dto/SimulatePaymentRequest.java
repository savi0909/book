package com.aaztra.mockpg.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Request to simulate a payment completion.
 * This is a TEST-ONLY endpoint - in production, Razorpay SDK handles payment.
 */
public record SimulatePaymentRequest(

    @NotBlank(message = "Payment method is required")
    @Pattern(regexp = "^(wallet|card|upi|netbanking)$",
             message = "Method must be: wallet, card, upi, or netbanking")
    String method,

    // Wallet payment fields
    @Size(max = 100, message = "Wallet ID cannot exceed 100 characters")
    String walletId,  // Phone number or email for wallet

    // UPI payment fields
    @Size(max = 100, message = "VPA cannot exceed 100 characters")
    String vpa,  // UPI VPA like user@upi

    // Card payment fields
    @Size(min = 16, max = 16, message = "Card number must be 16 digits")
    @Pattern(regexp = "^[0-9]*$", message = "Card number must contain only digits")
    String cardNumber,

    @Size(min = 3, max = 4, message = "CVV must be 3-4 digits")
    String cvv,

    @Pattern(regexp = "^(0[1-9]|1[0-2])/[0-9]{2}$", message = "Expiry must be MM/YY format")
    String expiry,

    @Size(max = 100, message = "Card holder name cannot exceed 100 characters")
    String cardHolderName,

    // Netbanking fields
    @Size(max = 10, message = "Bank code cannot exceed 10 characters")
    String bankCode,

    // Common fields
    @Email(message = "Invalid email format")
    String email,

    @Pattern(regexp = "^[0-9]{10}$", message = "Contact must be 10 digit phone number")
    String contact,

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    String description,

    Map<String, String> notes
) {
    /**
     * Validate that required fields are present based on payment method.
     */
    public void validate() {
        switch (method) {
            case "wallet" -> {
                if (walletId == null || walletId.isBlank()) {
                    throw new IllegalArgumentException("walletId is required for wallet payments");
                }
            }
            case "upi" -> {
                if (vpa == null || vpa.isBlank()) {
                    throw new IllegalArgumentException("vpa is required for UPI payments");
                }
            }
            case "card" -> {
                if (cardNumber == null || cvv == null || expiry == null) {
                    throw new IllegalArgumentException("cardNumber, cvv, and expiry are required for card payments");
                }
            }
            case "netbanking" -> {
                if (bankCode == null || bankCode.isBlank()) {
                    throw new IllegalArgumentException("bankCode is required for netbanking payments");
                }
            }
        }
    }
}

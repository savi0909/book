package com.aaztra.mockpg.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates mock gateway IDs in Razorpay format
 */
@Component
public class MockIdGenerator {

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_LENGTH = 14;
    private final SecureRandom random = new SecureRandom();

    public String generateOrderId() {
        return "order_mock_" + generateRandomString();
    }

    public String generatePaymentId() {
        return "pay_mock_" + generateRandomString();
    }

    public String generateRefundId() {
        return "rfnd_mock_" + generateRandomString();
    }

    public String generateEventId() {
        return "evt_mock_" + generateRandomString();
    }

    private String generateRandomString() {
        StringBuilder sb = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}

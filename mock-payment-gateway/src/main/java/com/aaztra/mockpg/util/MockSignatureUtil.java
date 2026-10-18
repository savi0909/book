package com.aaztra.mockpg.util;

import com.aaztra.mockpg.config.MockPgProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Utility for generating and verifying HMAC-SHA256 webhook signatures.
 * Compatible with Razorpay's webhook signature format.
 */
@Component
public class MockSignatureUtil {

    private static final Logger log = LoggerFactory.getLogger(MockSignatureUtil.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final String webhookSecret;

    public MockSignatureUtil(MockPgProperties properties) {
        this.webhookSecret = properties.getWebhookSecret();
    }

    /**
     * Generate HMAC-SHA256 signature using default webhook secret.
     *
     * @param payload JSON payload string
     * @return Hex-encoded signature
     */
    public String generateSignature(String payload) {
        return generateSignature(payload, webhookSecret);
    }

    /**
     * Generate HMAC-SHA256 signature with specified secret.
     *
     * @param payload JSON payload string
     * @param secret Secret key for HMAC
     * @return Hex-encoded signature
     */
    public String generateSignature(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    HMAC_SHA256
            );
            mac.init(secretKeySpec);

            byte[] signatureBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(signatureBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Failed to generate signature", e);
            throw new RuntimeException("Signature generation failed", e);
        }
    }

    /**
     * Verify webhook signature using default secret.
     *
     * @param payload JSON payload string
     * @param signature Received signature to verify
     * @return true if signature is valid
     */
    public boolean verifySignature(String payload, String signature) {
        return verifySignature(payload, signature, webhookSecret);
    }

    /**
     * Verify webhook signature with specified secret.
     *
     * @param payload JSON payload string
     * @param signature Received signature to verify
     * @param secret Secret key for HMAC
     * @return true if signature is valid
     */
    public boolean verifySignature(String payload, String signature, String secret) {
        if (payload == null || signature == null) {
            log.warn("Null payload or signature");
            return false;
        }

        String expectedSignature = generateSignature(payload, secret);
        boolean valid = constantTimeEquals(expectedSignature, signature);

        if (!valid) {
            log.warn("Signature verification failed. Expected: {}, Received: {}",
                     truncate(expectedSignature), truncate(signature));
        }

        return valid;
    }

    /**
     * Generate signature for Razorpay payment verification.
     * Razorpay format: order_id + "|" + payment_id
     *
     * @param orderId Gateway order ID
     * @param paymentId Gateway payment ID
     * @return Hex-encoded signature
     */
    public String generatePaymentSignature(String orderId, String paymentId) {
        String payload = orderId + "|" + paymentId;
        return generateSignature(payload);
    }

    /**
     * Verify Razorpay payment signature.
     *
     * @param orderId Gateway order ID
     * @param paymentId Gateway payment ID
     * @param signature Signature to verify
     * @return true if valid
     */
    public boolean verifyPaymentSignature(String orderId, String paymentId, String signature) {
        String payload = orderId + "|" + paymentId;
        return verifySignature(payload, signature);
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private String truncate(String s) {
        if (s == null) return "null";
        return s.length() > 16 ? s.substring(0, 16) + "..." : s;
    }
}

package com.aaztra.mockpg.dto;

import com.aaztra.mockpg.entity.MockPayment;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Mock payment response - Razorpay Payments API compatible format.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MockPaymentResponse(

    @JsonProperty("id")
    String id,

    @JsonProperty("entity")
    String entity,

    @JsonProperty("amount")
    Long amount,

    @JsonProperty("currency")
    String currency,

    @JsonProperty("status")
    String status,

    @JsonProperty("order_id")
    String orderId,

    @JsonProperty("method")
    String method,

    @JsonProperty("description")
    String description,

    @JsonProperty("email")
    String email,

    @JsonProperty("contact")
    String contact,

    // Wallet specific
    @JsonProperty("wallet")
    String wallet,

    // UPI specific
    @JsonProperty("vpa")
    String vpa,

    // Card specific
    @JsonProperty("card_id")
    String cardId,

    @JsonProperty("card")
    CardDetails card,

    // Netbanking specific
    @JsonProperty("bank")
    String bank,

    @JsonProperty("international")
    Boolean international,

    @JsonProperty("amount_refunded")
    Long amountRefunded,

    @JsonProperty("refund_status")
    String refundStatus,

    @JsonProperty("captured")
    Boolean captured,

    @JsonProperty("captured_at")
    Long capturedAt,

    @JsonProperty("fee")
    Long fee,

    @JsonProperty("tax")
    Long tax,

    // Error fields
    @JsonProperty("error_code")
    String errorCode,

    @JsonProperty("error_description")
    String errorDescription,

    @JsonProperty("error_source")
    String errorSource,

    @JsonProperty("error_step")
    String errorStep,

    @JsonProperty("error_reason")
    String errorReason,

    @JsonProperty("notes")
    Map<String, String> notes,

    @JsonProperty("created_at")
    Long createdAt
) {
    /**
     * Card details sub-object.
     */
    public record CardDetails(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("last4") String last4,
        @JsonProperty("network") String network,
        @JsonProperty("type") String type,
        @JsonProperty("issuer") String issuer
    ) {}

    /**
     * Create response from entity.
     */
    public static MockPaymentResponse from(MockPayment payment) {
        CardDetails cardDetails = null;
        if ("card".equals(payment.getMethod()) && payment.getCardLast4() != null) {
            cardDetails = new CardDetails(
                payment.getCardId(),
                null,  // Card holder name not stored
                payment.getCardLast4(),
                payment.getCardNetwork(),
                payment.getCardType(),
                null   // Issuer not stored
            );
        }

        return new MockPaymentResponse(
            payment.getPaymentId(),
            "payment",
            payment.getAmount(),
            payment.getCurrency(),
            payment.getStatus(),
            payment.getOrderId(),
            payment.getMethod(),
            payment.getDescription(),
            payment.getEmail(),
            payment.getContact(),
            payment.getWallet(),
            payment.getVpa(),
            payment.getCardId(),
            cardDetails,
            payment.getBank(),
            payment.getInternational(),
            payment.getAmountRefunded(),
            payment.getRefundStatus(),
            payment.getCaptured(),
            payment.getCapturedAt(),
            payment.getFee(),
            payment.getTax(),
            payment.getErrorCode(),
            payment.getErrorDescription(),
            payment.getErrorSource(),
            payment.getErrorStep(),
            payment.getErrorReason(),
            payment.getNotes(),
            payment.getCreatedAt()
        );
    }
}

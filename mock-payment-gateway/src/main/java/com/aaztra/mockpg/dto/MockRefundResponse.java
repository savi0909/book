package com.aaztra.mockpg.dto;

import com.aaztra.mockpg.entity.MockRefund;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Mock refund response - Razorpay Refunds API compatible format.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MockRefundResponse(

    @JsonProperty("id")
    String id,

    @JsonProperty("entity")
    String entity,

    @JsonProperty("amount")
    Long amount,

    @JsonProperty("currency")
    String currency,

    @JsonProperty("payment_id")
    String paymentId,

    @JsonProperty("receipt")
    String receipt,

    @JsonProperty("status")
    String status,

    @JsonProperty("speed_requested")
    String speedRequested,

    @JsonProperty("speed_processed")
    String speedProcessed,

    @JsonProperty("notes")
    Map<String, String> notes,

    @JsonProperty("acquirer_data")
    Map<String, String> acquirerData,

    @JsonProperty("created_at")
    Long createdAt,

    @JsonProperty("processed_at")
    Long processedAt
) {
    /**
     * Create response from entity.
     */
    public static MockRefundResponse from(MockRefund refund) {
        return new MockRefundResponse(
            refund.getRefundId(),
            "refund",
            refund.getAmount(),
            refund.getCurrency(),
            refund.getPaymentId(),
            refund.getReceipt(),
            refund.getStatus(),
            refund.getSpeedRequested(),
            refund.getSpeedProcessed(),
            refund.getNotes(),
            refund.getAcquirerData(),
            refund.getCreatedAt(),
            refund.getProcessedAt()
        );
    }
}

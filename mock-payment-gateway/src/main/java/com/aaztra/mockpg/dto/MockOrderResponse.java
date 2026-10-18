package com.aaztra.mockpg.dto;

import com.aaztra.mockpg.entity.MockOrder;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Mock order response - Razorpay Orders API compatible format.
 */
public record MockOrderResponse(

    @JsonProperty("id")
    String id,

    @JsonProperty("entity")
    String entity,

    @JsonProperty("amount")
    Long amount,

    @JsonProperty("amount_paid")
    Long amountPaid,

    @JsonProperty("amount_due")
    Long amountDue,

    @JsonProperty("currency")
    String currency,

    @JsonProperty("receipt")
    String receipt,

    @JsonProperty("status")
    String status,

    @JsonProperty("attempts")
    Integer attempts,

    @JsonProperty("notes")
    Map<String, String> notes,

    @JsonProperty("created_at")
    Long createdAt
) {
    /**
     * Create response from entity.
     */
    public static MockOrderResponse from(MockOrder order) {
        return new MockOrderResponse(
            order.getOrderId(),
            "order",
            order.getAmount(),
            order.getAmountPaid(),
            order.getAmountDue(),
            order.getCurrency(),
            order.getReceipt(),
            order.getStatus(),
            order.getAttempts(),
            order.getNotes(),
            order.getCreatedAt()
        );
    }
}

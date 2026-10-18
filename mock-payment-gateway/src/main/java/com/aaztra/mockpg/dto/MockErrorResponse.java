package com.aaztra.mockpg.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Error response - Razorpay API error format compatible.
 */
public record MockErrorResponse(

    @JsonProperty("error")
    ErrorDetails error
) {
    public record ErrorDetails(
        @JsonProperty("code") String code,
        @JsonProperty("description") String description,
        @JsonProperty("source") String source,
        @JsonProperty("step") String step,
        @JsonProperty("reason") String reason,
        @JsonProperty("metadata") Object metadata,
        @JsonProperty("field") String field
    ) {}

    public static MockErrorResponse of(String code, String description) {
        return new MockErrorResponse(
            new ErrorDetails(code, description, "internal", "processing", "server_error", null, null)
        );
    }

    public static MockErrorResponse of(String code, String description, String source, String reason) {
        return new MockErrorResponse(
            new ErrorDetails(code, description, source, "processing", reason, null, null)
        );
    }

    public static MockErrorResponse badRequest(String description, String field) {
        return new MockErrorResponse(
            new ErrorDetails("BAD_REQUEST_ERROR", description, "business", "input_validation", "input_validation_failed", null, field)
        );
    }
}

package com.example.booking;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

public final class OrderMessages {
    private OrderMessages() {}
    public static final String INVENTORY="booking.inventory.commands.v1", PAYMENT="booking.payment.commands.v1",
        REPLIES="booking.order.replies.v1", EVENTS="booking.order.events.v1";
    public static final Set<String> TOPICS=Set.of(INVENTORY,PAYMENT,REPLIES,EVENTS);
    public record Create(@NotNull UUID showId,@NotNull @Size(min=1,max=10) List<@NotNull @Min(1) @Max(500) Integer> seatNumbers) {}
    public record Envelope(UUID messageId,String type,int schemaVersion,UUID orderId,UUID sagaId,UUID commandId,
                           UUID correlationId,UUID causationId,Instant occurredAt,JsonNode payload) {
        public void validate() {
            if(messageId==null || type==null || type.length()>80 || schemaVersion!=1 || orderId==null || sagaId==null ||
                commandId==null || correlationId==null || occurredAt==null || payload==null || !payload.isObject())
                throw new IllegalArgumentException("Invalid versioned envelope");
        }
    }
    static UUID identity(UUID saga,String action) {
        return UUID.nameUUIDFromBytes((saga+"/"+action).getBytes(StandardCharsets.UTF_8));
    }
}

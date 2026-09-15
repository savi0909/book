package com.example.booking;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class Models {
    private Models() {}
    public enum Scenario { SUCCESS, FAILURE, UNKNOWN }
    public enum Outcome { SUCCESS, FAILURE }
    public record EventRequest(@NotBlank @Size(max = 80) String name,
                               @NotNull @Min(1) @Max(200) Integer seatCount) {}
    public record HoldRequest(@NotNull UUID eventId,
                              @NotNull @Min(1) @Max(200) Integer seatNumber,
                              @NotBlank @Pattern(regexp = "[A-Za-z0-9_.:-]{1,64}") String buyerId,
                              @NotNull @Min(2) @Max(120) Integer ttlSeconds) {}
    public record CheckoutRequest(@NotNull Scenario scenario,
                                  @NotNull @Min(0) @Max(10000) Integer delayMs) {}
    public record CallbackRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.:/-]{1,128}") String eventId,
                                  @NotNull Outcome outcome) {}
    public record Event(UUID id, String name, int seatCount, int priceMinor, String currency, Instant createdAt) {}
    public record Seat(int seatNumber, String availability) {}
    public record Payment(UUID id, UUID bookingId, Scenario scenario, int delayMs, String state,
                          Instant readyAt, int attempts) {}
    public record Booking(UUID id, UUID eventId, int seatNumber, String buyerId, String state,
                          String reconciliation, Instant expiresAt, Instant createdAt,
                          Payment payment, boolean replayed) {}
    record BookingRow(UUID id, UUID eventId, int seatNumber, String buyerId, String state,
                      String reconciliation, Instant expiresAt, Instant createdAt,
                      String holdKey, int ttlSeconds) {}
    record PaymentRow(Payment payment, String checkoutKey, UUID leaseToken) {}
    record Receipt(Outcome outcome, boolean ready) {}
}

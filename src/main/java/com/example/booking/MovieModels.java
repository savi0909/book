package com.example.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MovieModels {
    private MovieModels() {}
    public enum Category { A, B, C }
    public record ScreenRequest(@NotBlank @Size(max=80) String name,
            @NotNull @Min(200) @Max(500) Integer seatCount,
            @Size(min=2,max=3) Map<Category,@Min(1) @Max(99) Integer> categories) {
        public ScreenRequest { if(categories==null) categories=Map.of(Category.A,10,Category.B,20,Category.C,70); }
    }
    public record MultiplexRequest(@NotBlank @Size(max=100) String name,
            @NotBlank @Size(max=80) String zoneId,
            @NotNull @Size(min=5,max=100) List<@NotNull @Valid ScreenRequest> screens) {}
    public record Screen(UUID id,String name,int seatCount,Map<Category,Integer> categorySeats) {}
    public record Multiplex(UUID id,String name,String zoneId,List<Screen> screens) {}
    public record MultiplexSummary(UUID id,String name,String brand,String city,String state,int screenCount) {}
    public record NowShowing(UUID movieId,String title,String language,int durationMinutes,boolean blockbuster) {}
    public record MovieRequest(@NotBlank @Size(max=100) String title,
            @NotBlank @Size(max=40) String language,@NotNull @Min(1) @Max(360) Integer durationMinutes) {}
    public record Movie(UUID id,String title,String language,int durationMinutes) {}
    public record ShowRequest(@NotNull UUID movieId,@NotNull UUID screenId,@NotNull Instant startsAt,
            @NotNull @Min(0) @Max(120) Integer turnaroundMinutes,
            @NotNull @Size(min=2,max=3) Map<Category,@NotNull @Min(1) @Max(1000000) Integer> pricesMinor) {}
    public record Show(UUID id,UUID movieId,UUID screenId,UUID multiplexId,String title,String screenName,
            String zoneId,Instant startsAt,Instant endsAt,Instant occupiedUntil,int seatCount) {}
    public record Seat(int seatNumber,Category category,int priceMinor,String availability) {}
    public record HoldRequest(@NotNull UUID showId,
            @NotNull @Size(min=1,max=10) List<@NotNull @Min(1) @Max(500) Integer> seatNumbers,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{1,64}") String buyerId,
            @NotNull @Min(2) @Max(900) Integer ttlSeconds) {}
    public record CheckoutRequest(@Min(0) @Max(10000) Integer delayMs,@Min(0) @Max(9999) Integer testBucket) {
        public CheckoutRequest { if(delayMs==null) delayMs=0; }
    }
    public record SelectedSeat(int seatNumber,Category category,int priceMinor) {}
    public record Payment(UUID id,UUID bookingId,int paymentNumber,String state,int providerStep,
            int dispatches,long amountMinor,boolean refundRequired) {}
    public record Booking(UUID id,UUID showId,String buyerId,String state,Instant expiresAt,
            List<SelectedSeat> seats,long amountMinor,String currency,Payment payment,boolean replayed) {}
    public record Checkout(Booking booking,Payment payment,boolean replayed) {}
    record BookingRow(UUID id,UUID showId,String buyerId,String state,Instant expiresAt,
            long amountMinor,String holdKey,int ttlSeconds,UUID currentPaymentId) {}
    record PaymentRow(Payment payment,String checkoutKey,int planBucket,Integer requestedTestBucket,
            int delayMs,UUID leaseToken) {}
}

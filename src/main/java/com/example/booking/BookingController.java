package com.example.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.example.booking.Models.*;

@RestController
@Validated
public class BookingController {
    private final BookingService bookings;
    private final PaymentProcessor payments;
    private final String instance;
    private final CheckoutResponseDelay responseDelay;
    public BookingController(BookingService bookings,PaymentProcessor payments,@Value("${lab.instance}") String instance,
                             CheckoutResponseDelay responseDelay) {
        this.bookings=bookings;this.payments=payments;this.instance=instance;this.responseDelay=responseDelay;
    }
    @GetMapping("/health") Map<String,String> health() {return Map.of("status","up","instance",instance);}
    @PostMapping("/api/demo/events") ResponseEntity<Event> create(@Valid @RequestBody EventRequest request) {
        return ResponseEntity.status(201).body(bookings.createEvent(request));
    }
    @GetMapping("/api/events") List<Event> events(@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit,
                                                @RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset) {return bookings.events(limit,offset);}
    @GetMapping("/api/events/{id}") Event event(@PathVariable UUID id) {return bookings.event(id);}
    @GetMapping("/api/events/{id}/seats") List<Seat> seats(@PathVariable UUID id,@RequestParam(defaultValue="100") @Min(1) @Max(100) int limit,
                                                        @RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset) {return bookings.seats(id,limit,offset);}
    @PostMapping("/api/holds") ResponseEntity<Booking> hold(@Valid @RequestBody HoldRequest request,@RequestHeader("Idempotency-Key") String key) {
        Booking result=bookings.hold(request,key);
        return ResponseEntity.status(result.replayed()?200:201).body(result);
    }
    @GetMapping("/api/bookings/{id}") Booking get(@PathVariable UUID id) {return bookings.get(id);}
    @GetMapping("/api/bookings/{id}/audit") List<Map<String,Object>> audit(@PathVariable UUID id) {return bookings.audit(id);}
    @PostMapping("/api/bookings/{id}/checkout") ResponseEntity<Booking> checkout(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,
                                                                            @Valid @RequestBody CheckoutRequest request,
                                     @RequestHeader(value="X-Lab-Response-Delay-Ms",defaultValue="0") int delayMs) {
        responseDelay.validate(delayMs);
        Booking result=bookings.checkout(id,key,request);
        responseDelay.afterCommit(id,delayMs);
        return ResponseEntity.status(result.replayed()?200:202).body(result);
    }
    @PostMapping("/api/bookings/{id}/cancel") Booking cancel(@PathVariable UUID id) {return bookings.cancel(id);}
    @GetMapping("/api/payments/{id}") Payment payment(@PathVariable UUID id) {return payments.get(id);}
    @PostMapping("/api/demo/payments/{id}/callback") Booking callback(@PathVariable UUID id,@Valid @RequestBody CallbackRequest request) {return payments.callback(id,request);}
    @PostMapping("/api/demo/payments/{id}/reconcile") Booking reconcile(@PathVariable UUID id) {return payments.reconcile(id);}
    @PostMapping("/api/demo/payments/{id}/refund") Booking refund(@PathVariable UUID id) {return bookings.refund(id);}
    @GetMapping("/api/stats") Map<String,Object> stats() {return bookings.stats();}
}

package com.example.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;
import static com.example.booking.MovieModels.*;

@RestController
@Validated
public class MovieController {
    private final MovieCatalog catalog;
    private final MovieBookingService bookings;
    private final MoviePaymentService payments;
    public MovieController(MovieCatalog catalog,MovieBookingService bookings,MoviePaymentService payments) {
        this.catalog=catalog;this.bookings=bookings;this.payments=payments;
    }
    @PostMapping("/api/demo/movie/multiplexes") ResponseEntity<Multiplex> multiplex(@Valid @RequestBody MultiplexRequest r) { return ResponseEntity.status(201).body(catalog.createMultiplex(r)); }
    @GetMapping("/api/movie/multiplexes/{id}") Multiplex multiplex(@PathVariable UUID id) { return catalog.multiplex(id); }
    @GetMapping("/api/movie/multiplexes") List<MultiplexSummary> catalogMultiplexes(@RequestParam(required=false) String city) { return catalog.catalogMultiplexes(city); }
    @GetMapping("/api/movie/now-showing") List<NowShowing> nowShowing() { return catalog.nowShowing(); }
    @PostMapping("/api/demo/movie/movies") ResponseEntity<Movie> movie(@Valid @RequestBody MovieRequest r) { return ResponseEntity.status(201).body(catalog.createMovie(r)); }
    @PostMapping("/api/demo/movie/shows") ResponseEntity<Show> show(@Valid @RequestBody ShowRequest r) { return ResponseEntity.status(201).body(catalog.createShow(r)); }
    @GetMapping("/api/movie/shows") List<Show> shows(@RequestParam UUID multiplexId,@RequestParam LocalDate date,
            @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int offset) { return catalog.shows(multiplexId,date,limit,offset); }
    @GetMapping("/api/movie/shows/{id}") Show show(@PathVariable UUID id) { return catalog.show(id); }
    @GetMapping("/api/movie/shows/{id}/seats") List<Seat> seats(@PathVariable UUID id,
            @RequestParam(defaultValue="500") @Min(1) @Max(500) int limit,@RequestParam(defaultValue="0") @Min(0) @Max(500) int offset) { return catalog.seats(id,limit,offset); }
    @PostMapping("/api/movie/holds") ResponseEntity<Booking> hold(@Valid @RequestBody HoldRequest r,@RequestHeader("Idempotency-Key") String key) {
        var result=bookings.hold(r,key); return ResponseEntity.status(result.replayed()?200:201).body(result);
    }
    @GetMapping("/api/movie/bookings/{id}") Booking booking(@PathVariable UUID id) { return bookings.get(id); }
    @PostMapping("/api/movie/bookings/{id}/cancel") Booking cancel(@PathVariable UUID id) { return bookings.cancel(id); }
    @GetMapping("/api/movie/bookings/{id}/audit") List<Map<String,Object>> audit(@PathVariable UUID id) { return bookings.audit(id); }
    @PostMapping("/api/movie/bookings/{id}/checkout") ResponseEntity<Checkout> checkout(@PathVariable UUID id,
            @Valid @RequestBody CheckoutRequest r,@RequestHeader("Idempotency-Key") String key) {
        var result=payments.checkout(id,key,r); return ResponseEntity.status(result.replayed()?200:202).body(result);
    }
    @GetMapping("/api/movie/bookings/{id}/payments") List<Payment> history(@PathVariable UUID id) { return payments.history(id); }
    @GetMapping("/api/movie/payments/{id}") Payment payment(@PathVariable UUID id) { return payments.get(id); }
    @PostMapping("/api/demo/movie/payments/{id}/reconcile") Booking reconcile(@PathVariable UUID id) { return payments.manualReconcile(id); }
    @GetMapping("/api/movie/stats") Map<String,Object> stats() { return bookings.stats(); }
}

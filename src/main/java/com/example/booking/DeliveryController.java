package com.example.booking;

import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class DeliveryController {
    private final OutboxDispatcher dispatcher;
    public DeliveryController(OutboxDispatcher dispatcher) {this.dispatcher=dispatcher;}
    @GetMapping("/api/bookings/{id}/delivery") Map<String,Object> delivery(@PathVariable UUID id) {return dispatcher.bookingDelivery(id);}
    @GetMapping("/api/outbox/status") Map<String,Object> status() {return dispatcher.status();}
}

package com.example.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Unauthenticated local controls, enabled only by the optional failover overlay. */
@RestController
@RequestMapping("/api/demo/failover")
@ConditionalOnProperty(name="lab.failure-controls-enabled", havingValue="true")
public class FailoverController {
    private final AdmissionGate gate;
    private final CheckoutResponseDelay delay;
    private final String instance;
    public FailoverController(AdmissionGate gate, CheckoutResponseDelay delay,
                              @Value("${lab.instance}") String instance) {
        this.gate = gate; this.delay = delay; this.instance = instance;
    }
    @GetMapping("/status") public Map<String,Object> status() {
        return Map.of("instance", instance, "admission", gate.status(), "waitingBookings", delay.waitingBookings());
    }
    @PostMapping("/drain") public Map<String,Object> drain() { gate.drain(); return status(); }
    @PostMapping("/resume") public Map<String,Object> resume() { gate.resume(); return status(); }
}

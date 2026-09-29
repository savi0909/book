package com.example.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Public local teaching controls, absent unless explicitly enabled. */
@RestController
@RequestMapping("/api/demo/recovery")
@ConditionalOnProperty(name="lab.poison-controls-enabled",havingValue="true")
public class PoisonController {
    private final RecoveryIsolation isolation;
    private final PaymentProcessor payments;
    private final boolean maintenanceEnabled;
    public PoisonController(RecoveryIsolation isolation,PaymentProcessor payments,@Value("${lab.maintenance-enabled:true}") boolean maintenanceEnabled) {
        this.isolation=isolation;this.payments=payments;this.maintenanceEnabled=maintenanceEnabled;
    }
    @GetMapping("/controls") Map<String,Object> controls() {return Map.of("maintenanceEnabled",maintenanceEnabled,"fixturesEnabled",true);}
    record Fixture(@NotNull Boolean enabled) { }
    @PostMapping("/payments/{id}/fixture") Map<String,Object> fixture(@PathVariable UUID id,@Valid @RequestBody Fixture request) {
        return isolation.fixture(id,request.enabled());
    }
    @PostMapping("/payments/{id}/redrive") Map<String,Object> redrive(@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key) {
        return payments.redrive(id,key);
    }
    @PostMapping("/tick") Map<String,Object> tick() {return Map.of("candidates",payments.recoverBatch());}
}

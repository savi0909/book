package com.example.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Maintenance {
    private static final Logger LOG=LoggerFactory.getLogger(Maintenance.class);
    private final BookingService bookings;
    private final PaymentProcessor payments;
    private final boolean enabled;
    private final AdmissionGate gate;
    public Maintenance(BookingService bookings,PaymentProcessor payments,@Value("${lab.maintenance-enabled:true}") boolean enabled,
                       AdmissionGate gate) {
        this.bookings=bookings;this.payments=payments;this.enabled=enabled;this.gate=gate;
    }
    @Scheduled(fixedDelay=500,initialDelay=1000)
    public void tick() {
        if(!enabled || !gate.enter()) return;
        try {bookings.expireBatch();payments.recoverBatch();}
        catch(RuntimeException error) {LOG.warn("Maintenance will retry: {}",error.getClass().getSimpleName());}
        finally {gate.leave();}
    }
}

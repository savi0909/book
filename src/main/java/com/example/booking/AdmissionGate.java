package com.example.booking;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Admission and drain share one monitor so no new work enters after drain returns. */
@Component
public class AdmissionGate {
    private final ConfigurableApplicationContext context;
    private boolean draining;
    private boolean stopping;
    private int inFlight;

    public AdmissionGate(ConfigurableApplicationContext context) { this.context = context; }
    public synchronized boolean enter() {
        if (draining) return false;
        inFlight++;
        return true;
    }
    public synchronized void leave() { inFlight--; }
    public synchronized Map<String,Object> status() {
        return Map.of("draining", draining, "inFlight", inFlight, "stopping", stopping);
    }
    public synchronized Map<String,Object> drain() {
        draining = true;
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        return status();
    }
    public synchronized Map<String,Object> resume() {
        if (stopping) throw ApiException.conflict("INSTANCE_STOPPING", "Restart this instance to resume");
        draining = false;
        AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        return status();
    }
    @EventListener(ContextClosedEvent.class)
    public synchronized void closing() { stopping = true; draining = true; }
}

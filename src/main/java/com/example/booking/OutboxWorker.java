package com.example.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxWorker {
    private static final Logger LOG=LoggerFactory.getLogger(OutboxWorker.class);
    private final OutboxDispatcher dispatcher;
    private final AdmissionGate gate;
    private final boolean enabled;
    public OutboxWorker(OutboxDispatcher dispatcher,AdmissionGate gate,
                        @Value("${lab.maintenance-enabled:true}") boolean maintenance,
                        @Value("${lab.outbox-dispatch-enabled:true}") boolean dispatch) {
        this.dispatcher=dispatcher;this.gate=gate;this.enabled=maintenance && dispatch;
    }
    public boolean automatic() {return enabled;}
    @Scheduled(fixedDelay=500,initialDelay=1500)
    public void tick() {
        if(!enabled || !gate.enter()) return;
        try {dispatcher.dispatchBatch();}
        catch(RuntimeException error) {LOG.warn("Outbox delivery will retry: {}",error.getClass().getSimpleName());}
        finally {gate.leave();}
    }
}

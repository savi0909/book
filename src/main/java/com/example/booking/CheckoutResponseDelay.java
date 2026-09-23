package com.example.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Local exercise hook. The caller invokes it only after checkout's transaction returns. */
@Component
public class CheckoutResponseDelay {
    private final boolean enabled;
    private final ConcurrentLinkedQueue<UUID> waiting = new ConcurrentLinkedQueue<>();
    public CheckoutResponseDelay(@Value("${lab.failure-controls-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }
    public void validate(int milliseconds) {
        if (milliseconds < 0 || milliseconds > 10000)
            throw new ApiException(400, "INVALID_RESPONSE_DELAY", "Response delay must be 0..10000 milliseconds");
        if (milliseconds > 0 && !enabled)
            throw new ApiException(404, "DEMO_CONTROL_DISABLED", "Response delay is disabled");
    }
    public void afterCommit(UUID booking, int milliseconds) {
        if (milliseconds == 0) return;
        waiting.add(booking);
        try { Thread.sleep(milliseconds); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally { waiting.remove(booking); }
    }
    public List<UUID> waitingBookings() { return List.copyOf(waiting); }
}

package com.example.booking;

import org.springframework.stereotype.Component;
import java.util.UUID;
import static com.example.booking.Models.*;

/** Local durable simulator only; never sends a network request or processes card data. */
@Component
public class LocalProvider {
    private final BookingStore store;
    public LocalProvider(BookingStore store) { this.store=store; }
    Receipt accept(Payment p) {
        // This transaction commits separately from inventory and callback processing.
        store.tx(() -> {
            store.jdbc().update("INSERT INTO provider_receipts(payment_id,outcome,available_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                p.id(),p.scenario()==Scenario.FAILURE ? "FAILURE":"SUCCESS",java.sql.Timestamp.from(p.readyAt()));
            return null;
        });
        return lookup(p.id());
    }
    Receipt lookup(UUID id) {
        return BookingStore.one(store.jdbc().query("SELECT outcome, available_at <= clock_timestamp() AS ready FROM provider_receipts WHERE payment_id=?",
            (rs,n)->new Receipt(Outcome.valueOf(rs.getString("outcome")),rs.getBoolean("ready")),id));
    }
}

package com.example.booking;

import org.springframework.stereotype.Component;
import java.util.UUID;
import static com.example.booking.Models.*;

/** Local simulator or opt-in independent HTTP stub; never processes real payments. */
@Component
public class LocalProvider {
    private final BookingStore store;
    private final ProviderBoundary boundary;
    public LocalProvider(BookingStore store,ProviderBoundary boundary) { this.store=store;this.boundary=boundary; }
    boolean remote() {return boundary.enabled();}
    Receipt accept(Payment p) {
        if(boundary.enabled()) {
            String value=boundary.call("/payments/"+p.id(),(p.scenario()==Scenario.FAILURE?"FAILURE":"SUCCESS")+","+p.readyAt().toEpochMilli());
            String[] fields=value.split(",");
            if(fields.length!=2 || !fields[0].equals(p.scenario()==Scenario.FAILURE?"FAILURE":"SUCCESS") || Long.parseLong(fields[1])!=p.readyAt().toEpochMilli())
                throw new ProviderBoundary.Unavailable("INVALID_RECEIPT");
        }
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

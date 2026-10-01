package com.example.booking;

import org.springframework.stereotype.Service;
import java.util.*;

/** A retained local SQL effect, not email or a separate availability domain. */
@Service
public class LocalNotificationSink {
    private final BookingStore store;
    public LocalNotificationSink(BookingStore store) {this.store=store;}
    public Map<String,Object> consume(UUID eventId) {
        // Resolve an immutable committed snapshot. Never manufacture payload from current booking state.
        var events=store.jdbc().queryForList("SELECT * FROM booking_outbox WHERE id=?",eventId);
        if(events.isEmpty()) throw ApiException.missing();
        var event=events.getFirst();UUID booking=(UUID)event.get("booking_id");
        long version=((Number)event.get("booking_version")).longValue();String kind=(String)event.get("kind");
        return store.tx(() -> {
            store.jdbc().update("INSERT INTO booking_delivery_projection(booking_id) VALUES (?) ON CONFLICT DO NOTHING",booking);
            var projection=store.jdbc().queryForMap("SELECT * FROM booking_delivery_projection WHERE booking_id=? FOR UPDATE",booking);
            var seen=store.jdbc().queryForList("SELECT disposition FROM notification_inbox WHERE event_id=?",eventId);
            if(!seen.isEmpty()) {
                store.jdbc().update("UPDATE notification_inbox SET deliveries=deliveries+1 WHERE event_id=?",eventId);
                return Map.of("eventId",eventId,"replayed",true,"disposition",seen.getFirst().get("disposition"));
            }
            boolean newer=version>((Number)projection.get("booking_version")).longValue();
            String disposition=newer?"APPLIED":"STALE_IGNORED";
            store.jdbc().update("INSERT INTO notification_inbox(event_id,disposition) VALUES (?,?)",eventId,disposition);
            if(newer) {
                store.jdbc().update("UPDATE booking_delivery_projection SET booking_version=?,state=?,updated_at=clock_timestamp() WHERE booking_id=?",version,kind,booking);
                store.jdbc().update("INSERT INTO local_notification_receipts(event_id,booking_id,booking_version,kind) VALUES (?,?,?,?)",eventId,booking,version,kind);
            }
            return Map.of("eventId",eventId,"replayed",false,"disposition",disposition);
        });
    }
}

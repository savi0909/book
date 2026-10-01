package com.example.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OutboxDispatcher {
    private final BookingStore store;
    private final LocalNotificationSink sink;
    private final boolean controls;
    private final Set<UUID> waiting=ConcurrentHashMap.newKeySet();
    public OutboxDispatcher(BookingStore store,LocalNotificationSink sink,@Value("${lab.outbox-controls-enabled:false}") boolean controls) {
        this.store=store;this.sink=sink;this.controls=controls;
    }
    public int dispatchBatch() {
        var ids=store.jdbc().queryForList("""
            SELECT id FROM booking_outbox WHERE delivered_at IS NULL AND next_at<=clock_timestamp()
              AND (lease_until IS NULL OR lease_until<=clock_timestamp()) ORDER BY next_at,id LIMIT 20
            """,UUID.class);
        for(UUID id:ids) dispatch(id,false);
        return ids.size();
    }
    public boolean dispatch(UUID id,boolean force) {
        UUID token=UUID.randomUUID();
        var claimed=store.tx(() -> store.jdbc().queryForList("""
            UPDATE booking_outbox SET lease_token=?,lease_until=clock_timestamp()+interval '5 seconds',attempts=attempts+1
            WHERE id=? AND delivered_at IS NULL AND (? OR next_at<=clock_timestamp())
              AND (lease_until IS NULL OR lease_until<=clock_timestamp()) RETURNING demo_delay_ms
            """,token,id,force));
        if(claimed.isEmpty()) return false;
        try {
            // Three separate commits: claim, inbox+effect, acknowledgement. No inventory lock spans delivery.
            sink.consume(id);
            int delay=((Number)claimed.getFirst().get("demo_delay_ms")).intValue();
            if(controls && delay>0) {
                waiting.add(id);
                try {Thread.sleep(delay);}
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();return true;}
                finally {waiting.remove(id);}
            }
            acknowledge(id,token);
        } catch(org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException dependency) {
            throw dependency;
        } catch(RuntimeException itemError) {
            String reason=itemError.getClass().getSimpleName();if(reason.length()>64) reason=reason.substring(0,64);
            store.jdbc().update("""
                UPDATE booking_outbox SET last_error=?,next_at=clock_timestamp()+interval '2 seconds',lease_token=NULL,lease_until=NULL
                WHERE id=? AND lease_token=? AND lease_until>clock_timestamp() AND delivered_at IS NULL
                """,reason,id,token);
        }
        return true;
    }
    boolean acknowledge(UUID id,UUID token) {
        return store.jdbc().update("""
            UPDATE booking_outbox SET delivered_at=clock_timestamp(),lease_token=NULL,lease_until=NULL,last_error=NULL
            WHERE id=? AND lease_token=? AND lease_until>clock_timestamp() AND delivered_at IS NULL
            """,id,token)==1;
    }
    public Set<UUID> waitingEvents() {return Set.copyOf(waiting);}
    boolean eventMissing(UUID id) {return store.jdbc().queryForList("SELECT id FROM booking_outbox WHERE id=?",id).isEmpty();}
    public Map<String,Object> delay(UUID id,int milliseconds) {
        if(milliseconds<0 || milliseconds>10000) throw new ApiException(400,"INVALID_DELAY","Delay must be0..10000ms");
        if(store.jdbc().update("UPDATE booking_outbox SET demo_delay_ms=? WHERE id=?",milliseconds,id)==0) throw ApiException.missing();
        return Map.of("eventId",id,"delayMs",milliseconds);
    }
    public Map<String,Object> bookingDelivery(UUID booking) {
        store.row(booking);
        var result=new LinkedHashMap<String,Object>();
        result.put("bookingId",booking);
        result.put("events",store.jdbc().queryForList("""
            SELECT id,booking_version AS "bookingVersion",kind,schema_version AS "schemaVersion",attempts,
              created_at AS "createdAt",next_at AS "nextAt",delivered_at AS "deliveredAt",
              lease_until AS "leaseUntil",last_error AS "lastError"
            FROM booking_outbox WHERE booking_id=? ORDER BY booking_version LIMIT 100
            """,booking));
        result.put("projection",store.jdbc().queryForList("SELECT booking_version AS \"bookingVersion\",state FROM booking_delivery_projection WHERE booking_id=?",booking));
        result.put("inbox",store.jdbc().queryForList("""
            SELECT i.event_id AS "eventId",i.disposition,i.deliveries FROM notification_inbox i
            JOIN booking_outbox o ON o.id=i.event_id WHERE o.booking_id=? ORDER BY o.booking_version LIMIT 100
            """,booking));
        result.put("notifications",store.jdbc().queryForList("SELECT event_id AS \"eventId\",booking_version AS \"bookingVersion\",kind FROM local_notification_receipts WHERE booking_id=? ORDER BY booking_version LIMIT 100",booking));
        return result;
    }
    public Map<String,Object> status() {
        return store.jdbc().queryForMap("""
            SELECT count(*) AS pending,COALESCE(EXTRACT(EPOCH FROM clock_timestamp()-min(created_at)),0) AS "oldestSeconds",
              count(*) FILTER (WHERE attempts>1) AS retried FROM booking_outbox WHERE delivered_at IS NULL
            """);
    }
}

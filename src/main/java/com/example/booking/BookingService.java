package com.example.booking;

import org.springframework.stereotype.Service;
import java.util.*;
import static com.example.booking.Models.*;

@Service
public class BookingService {
    private final BookingStore store;
    private final ProviderBoundary boundary;
    public BookingService(BookingStore store,ProviderBoundary boundary) { this.store = store;this.boundary=boundary; }
    public Event createEvent(EventRequest request) {
        return store.tx(() -> {
            UUID id = UUID.randomUUID();
            store.jdbc().update("INSERT INTO events(id,name,seat_count) VALUES (?,?,?)", id, request.name(), request.seatCount());
            store.jdbc().update("INSERT INTO seats SELECT ?, n FROM generate_series(1,?) n", id, request.seatCount());
            return event(id);
        });
    }
    public Event event(UUID id) {
        return BookingStore.one(store.jdbc().query("SELECT * FROM events WHERE id = ?", (rs,n) -> new Event(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("seat_count"), rs.getInt("price_minor"),
            rs.getString("currency"), BookingStore.instant(rs,"created_at")), id));
    }
    public List<Event> events(int limit, int offset) {
        return store.jdbc().query("SELECT * FROM events ORDER BY created_at DESC, id LIMIT ? OFFSET ?", (rs,n) -> new Event(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("seat_count"), rs.getInt("price_minor"),
            rs.getString("currency"), BookingStore.instant(rs,"created_at")), limit, offset);
    }
    public List<Seat> seats(UUID event, int limit, int offset) {
        event(event);
        return store.jdbc().query("""
            SELECT s.seat_number, CASE WHEN b.state = 'CONFIRMED' THEN 'BOOKED'
              WHEN b.state IN ('HELD','CHECKOUT') AND b.expires_at > statement_timestamp() THEN 'HELD'
              ELSE 'AVAILABLE' END AS availability
            FROM seats s LEFT JOIN bookings b ON b.event_id = s.event_id AND b.seat_number = s.seat_number
              AND b.state IN ('HELD','CHECKOUT','CONFIRMED')
            WHERE s.event_id = ? ORDER BY s.seat_number LIMIT ? OFFSET ?
            """, (rs,n) -> new Seat(rs.getInt("seat_number"),rs.getString("availability")), event, limit, offset);
    }
    public Booking hold(HoldRequest r, String key) {
        validateKey(key);
        return store.tx(() -> {
            // Serialize the scoped idempotency key even when duplicate payloads target different seats.
            // Hash collisions only add contention; UNIQUE(buyer_id,hold_key) is the durable backstop.
            store.jdbc().queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", r.buyerId()+"/"+key);
            var existing = store.jdbc().query("SELECT * FROM bookings WHERE buyer_id = ? AND hold_key = ?", BookingStore.BOOKING, r.buyerId(), key);
            if (!existing.isEmpty()) {
                BookingRow b = existing.getFirst();
                if (!b.eventId().equals(r.eventId()) || b.seatNumber() != r.seatNumber() || b.ttlSeconds() != r.ttlSeconds())
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Key was used with different hold input");
                store.locked(b.id());
                store.expire(b.id());
                return store.view(b.id(), true);
            }
            store.lockSeat(r.eventId(), r.seatNumber());
            var active = store.jdbc().query("SELECT * FROM bookings WHERE event_id = ? AND seat_number = ? AND state IN ('HELD','CHECKOUT','CONFIRMED') FOR UPDATE",
                                          BookingStore.BOOKING, r.eventId(), r.seatNumber());
            if (!active.isEmpty()) {
                store.expire(active.getFirst().id());
                String state = store.row(active.getFirst().id()).state();
                if (Set.of("HELD","CHECKOUT","CONFIRMED").contains(state))
                    throw ApiException.conflict("SEAT_UNAVAILABLE", "Seat is already held or booked");
            }
            UUID id = UUID.randomUUID();
            store.jdbc().update("""
                INSERT INTO bookings(id,event_id,seat_number,buyer_id,hold_key,ttl_seconds,state,expires_at)
                VALUES (?,?,?,?,?,?,'HELD',clock_timestamp() + (? * interval '1 second'))
                """, id,r.eventId(),r.seatNumber(),r.buyerId(),key,r.ttlSeconds(),r.ttlSeconds());
            store.audit(id,"HELD");
            return store.view(id,false);
        });
    }
    public Booking get(UUID id) {
        return store.tx(() -> { store.locked(id); store.expire(id); return store.view(id,false); });
    }
    public Booking checkout(UUID id, String key, CheckoutRequest r) {
        validateKey(key);
        return store.tx(() -> {
            store.locked(id);
            var existing = store.jdbc().query("SELECT * FROM payments WHERE booking_id = ? FOR UPDATE", BookingStore.PAYMENT,id);
            if (!existing.isEmpty()) {
                PaymentRow p = existing.getFirst();
                if (!p.checkoutKey().equals(key) || p.payment().scenario()!=r.scenario() || p.payment().delayMs()!=r.delayMs())
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Only one checkout intent per booking; reuse its key and input");
                store.expire(id);
                return store.view(id,true);
            }
            if(boundary.enabled()) store.jdbc().queryForList("SELECT pg_advisory_xact_lock(81058106)");
            if(boundary.enabled() && !providerAdmission()) throw new ApiException(503,"PAYMENT_BACKLOG_FULL","New checkout paused; existing keys remain discoverable");
            // Expiry is tested by the actual mutation, after waiting for locks.
            int changed = store.jdbc().update("""
                UPDATE bookings SET state = 'CHECKOUT', updated_at = clock_timestamp()
                WHERE id = ? AND state = 'HELD' AND expires_at > clock_timestamp()
                """,id);
            if (changed==0) throw ApiException.conflict("HOLD_NOT_VALID", "Checkout requires a currently valid HELD booking");
            store.jdbc().update("""
                INSERT INTO payments(id,booking_id,checkout_key,scenario,delay_ms,ready_at)
                VALUES (?,?,?,?,?,clock_timestamp() + (? * interval '1 millisecond'))
                """,UUID.randomUUID(),id,key,r.scenario().name(),r.delayMs(),r.delayMs());
            store.audit(id,"CHECKOUT_STARTED");
            return store.view(id,false);
        });
    }
    public Booking cancel(UUID id) {
        return store.tx(() -> {
            store.locked(id);
            store.expire(id);
            BookingRow b = store.row(id);
            if (Set.of("HELD","CHECKOUT","CONFIRMED").contains(b.state())) {
                store.jdbc().update("UPDATE bookings SET state='CANCELLED',delivery_version=delivery_version+1,updated_at=clock_timestamp() WHERE id=?",id);
                Payment p = store.paymentFor(id);
                if (p != null && p.state().equals("SUCCESS"))
                    store.jdbc().update("UPDATE bookings SET reconciliation='REFUND_REQUIRED' WHERE id=?",id);
                store.audit(id,"CANCELLED");
                store.enqueueSnapshot(id,"CANCELLED");
                return store.view(id,false);
            }
            return store.view(id,true);
        });
    }
    public Booking refund(UUID payment) {
        UUID id = store.paymentRow(payment).payment().bookingId();
        return store.tx(() -> {
            store.locked(id);
            if (store.row(id).reconciliation().equals("REFUNDED_SIMULATED")) return store.view(id,true);
            if (!store.row(id).reconciliation().equals("REFUND_REQUIRED"))
                throw ApiException.conflict("NO_REFUND_REQUIRED", "Booking is not awaiting a simulated refund");
            store.jdbc().update("UPDATE bookings SET reconciliation='REFUNDED_SIMULATED',updated_at=clock_timestamp() WHERE id=?",id);
            store.audit(id,"REFUNDED_SIMULATED");
            return store.view(id,false);
        });
    }
    public List<Map<String,Object>> audit(UUID id) {
        store.row(id);
        return store.jdbc().queryForList("SELECT id,action,created_at AS \"createdAt\" FROM booking_audit WHERE booking_id=? ORDER BY id LIMIT 100",id);
    }
    boolean providerAdmission() {
        return Boolean.TRUE.equals(store.jdbc().queryForObject("""
            SELECT count(*) < 100 AND COALESCE(min(created_at)>clock_timestamp()-interval '30 seconds',true)
            FROM payments WHERE state IN ('PENDING','UNKNOWN')
            """,Boolean.class));
    }
    public Map<String,Object> providerBacklog() {
        return store.jdbc().queryForMap("""
            SELECT count(*) AS unresolved, count(*) FILTER (WHERE retry_exhausted) AS exhausted,
              count(*) FILTER (WHERE quarantined_at IS NOT NULL) AS quarantined,
              COALESCE(EXTRACT(EPOCH FROM clock_timestamp()-min(created_at)),0) AS "oldestSeconds"
            FROM payments WHERE state IN ('PENDING','UNKNOWN')
            """);
    }
    public Map<String,Object> stats() {
        return Map.of("bookings",store.jdbc().queryForList("SELECT state,count(*) AS count FROM bookings GROUP BY state ORDER BY state"),
                      "payments",store.jdbc().queryForList("SELECT state,count(*) AS count FROM payments GROUP BY state ORDER BY state"),
                      "refundRequired",store.jdbc().queryForObject("SELECT count(*) FROM bookings WHERE reconciliation='REFUND_REQUIRED'",Long.class));
    }
    public int expireBatch() {
        var ids=store.jdbc().queryForList("SELECT id FROM bookings WHERE state IN ('HELD','CHECKOUT') AND expires_at <= clock_timestamp() ORDER BY expires_at LIMIT 50",UUID.class);
        for(UUID id:ids) store.tx(() -> {store.locked(id);store.expire(id);return null;});
        return ids.size();
    }
    static void validateKey(String key) {
        if(key == null || !key.matches("[A-Za-z0-9_.:/-]{1,128}"))
            throw new ApiException(400,"INVALID_KEY","Idempotency-Key must contain 1..128 ASCII letters, digits, _ . : / or -");
    }
}

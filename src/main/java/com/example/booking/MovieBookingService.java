package com.example.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.example.booking.MovieModels.*;

@Service
public class MovieBookingService {
    private final MovieStore store;
    private final MovieCatalog catalog;
    private final int windowDays;
    public MovieBookingService(MovieStore store,MovieCatalog catalog,@Value("${movie.booking-window-days:3}") int windowDays) {
        this.store=store;this.catalog=catalog;this.windowDays=windowDays;
    }
    public Booking hold(HoldRequest r,String key) {
        BookingService.validateKey(key);
        List<Integer> numbers=r.seatNumbers().stream().sorted().toList();
        if(numbers.stream().distinct().count()!=numbers.size())
            throw new ApiException(400,"DUPLICATE_SEAT","Choose each seat only once");
        return store.db().tx(()-> {
            store.db().jdbc().queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","movie/"+r.buyerId()+"/"+key);
            var existing=store.db().jdbc().query("SELECT * FROM movie_bookings WHERE buyer_id=? AND hold_key=?",MovieStore.BOOKING,r.buyerId(),key);
            if(!existing.isEmpty()) {
                var b=existing.getFirst();
                if(!b.showId().equals(r.showId()) || b.ttlSeconds()!=r.ttlSeconds() ||
                        !store.selected(b.id()).stream().map(SelectedSeat::seatNumber).toList().equals(numbers))
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT","Key already binds another show, seat set or hold duration");
                store.locked(b.id()); store.expireLocked(b.id()); return store.view(b.id(),true);
            }
            catalog.show(r.showId());
            store.lockSeats(r.showId(),numbers);
            if(!Boolean.TRUE.equals(store.db().jdbc().queryForObject("SELECT starts_at>clock_timestamp() FROM movie_shows WHERE id=?",Boolean.class,r.showId())))
                throw ApiException.conflict("SHOW_STARTED","Cannot reserve seats after a show starts");
            if(!Boolean.TRUE.equals(store.db().jdbc().queryForObject("""
                SELECT (sh.starts_at AT TIME ZONE mx.zone_id)::date<=(clock_timestamp() AT TIME ZONE mx.zone_id)::date+?
                FROM movie_shows sh JOIN movie_screens s ON s.id=sh.screen_id JOIN movie_multiplexes mx ON mx.id=s.multiplex_id
                WHERE sh.id=?
                """,Boolean.class,windowDays,r.showId())))
                throw ApiException.conflict("SHOW_NOT_YET_OPEN","Booking opens "+windowDays+" days before the show's local date");
            var all=store.db().jdbc().query("""
                SELECT s.seat_number,s.category,s.price_minor,
                  (b.state='CONFIRMED' OR (b.state IN ('HELD','PAYMENT_PENDING') AND b.expires_at>clock_timestamp())) AS occupied
                FROM movie_show_seats s LEFT JOIN movie_bookings b ON b.id=s.active_booking_id
                WHERE s.show_id=? ORDER BY s.seat_number
                """,(rs,n)->Map.of("number",rs.getInt("seat_number"),"category",rs.getString("category"),
                    "price",rs.getInt("price_minor"),"occupied",rs.getBoolean("occupied")),r.showId());
            var seats=all.stream().filter(s->numbers.contains((Integer)s.get("number"))).toList();
            if(seats.stream().anyMatch(s->(Boolean)s.get("occupied")))
                throw ApiException.conflict("SEAT_UNAVAILABLE","At least one chosen seat is held or booked; no seats were reserved");
            long amount=seats.stream().mapToLong(s->(Integer)s.get("price")).sum();
            UUID id=UUID.randomUUID();
            store.db().jdbc().update("""
                INSERT INTO movie_bookings(id,show_id,buyer_id,hold_key,ttl_seconds,state,amount_minor,expires_at)
                SELECT ?,id,?,?,?,'HELD',?,LEAST(starts_at,clock_timestamp()+(? * interval '1 second'))
                FROM movie_shows WHERE id=?
                """,id,r.buyerId(),key,r.ttlSeconds(),amount,r.ttlSeconds(),r.showId());
            for(var seat:seats) {
                store.db().jdbc().update("INSERT INTO movie_booking_seats VALUES (?,?,?,?,?)",id,r.showId(),seat.get("number"),seat.get("category"),seat.get("price"));
                store.db().jdbc().update("UPDATE movie_show_seats SET active_booking_id=? WHERE show_id=? AND seat_number=?",id,r.showId(),seat.get("number"));
            }
            store.audit(id,"HELD"); return store.view(id,false);
        });
    }
    public Booking get(UUID id) { return store.db().tx(()-> {store.locked(id);store.expireLocked(id);return store.view(id,false);}); }
    public Booking cancel(UUID id) {
        return store.db().tx(()-> {
            var b=store.locked(id); store.expireLocked(id); b=store.row(id);
            if(Set.of("HELD","PAYMENT_PENDING","CONFIRMED").contains(b.state())) {
                store.db().jdbc().update("UPDATE movie_bookings SET state='CANCELLED' WHERE id=?",id);
                store.release(id);
                store.db().jdbc().update("UPDATE movie_payments SET refund_required=true WHERE booking_id=? AND state='SUCCEEDED'",id);
                store.audit(id,"CANCELLED"); return store.view(id,false);
            }
            return store.view(id,true);
        });
    }
    public List<Map<String,Object>> audit(UUID id) {
        store.row(id);
        return store.db().jdbc().queryForList("SELECT id,action,created_at AS \"createdAt\" FROM movie_booking_audit WHERE booking_id=? ORDER BY id LIMIT 100",id);
    }
    public int expireBatch() {
        var ids=store.db().jdbc().queryForList("SELECT id FROM movie_bookings WHERE state IN ('HELD','PAYMENT_PENDING') AND expires_at<=clock_timestamp() ORDER BY expires_at,id LIMIT 20",UUID.class);
        for(UUID id:ids) get(id);
        return ids.size();
    }
    public Map<String,Object> stats() {
        return Map.of("bookings",store.db().jdbc().queryForList("SELECT state,count(*) AS count FROM movie_bookings GROUP BY state ORDER BY state"),
            "payments",store.db().jdbc().queryForList("SELECT state,count(*) AS count FROM movie_payments GROUP BY state ORDER BY state"),
            "paymentPaths",store.db().jdbc().queryForMap("""
                SELECT count(*) FILTER (WHERE state='SUCCEEDED' AND provider_step=1) AS "firstCallSuccess",
                  count(*) FILTER (WHERE state='SUCCEEDED' AND provider_step=2) AS "retrySuccess",
                  count(*) FILTER (WHERE state='FAILED') AS "failed",count(*) FILTER (WHERE refund_required) AS "refundRequired",
                  count(*) FILTER (WHERE state IN ('PENDING','RETRY_PENDING')) AS "pending"
                FROM movie_payments
                """),
            "seatConflicts",store.db().jdbc().queryForObject("""
                SELECT count(*) FROM (SELECT h.show_id,h.seat_number FROM movie_booking_seats h
                  JOIN movie_bookings b ON b.id=h.booking_id
                  WHERE b.state='CONFIRMED' OR (b.state IN ('HELD','PAYMENT_PENDING') AND b.expires_at>statement_timestamp())
                  GROUP BY h.show_id,h.seat_number HAVING count(*)>1) violations
                """,Long.class));
    }
}

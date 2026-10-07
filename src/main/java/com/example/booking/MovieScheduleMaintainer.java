package com.example.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Keeps catalog screens scheduled for today..today+window (multiplex-local dates) and
 * deletes whole past days with every dependent booking row. Runs on its own thread so
 * long fills never delay the payment/expiry loops on Spring's single scheduler thread.
 * Each batch takes a transaction advisory lock; a replica that loses it skips the tick.
 */
@Component
public class MovieScheduleMaintainer {
    private static final Logger LOG=LoggerFactory.getLogger(MovieScheduleMaintainer.class);
    static final long LOCK_KEY=0x4d6f766965L; // "Movie"
    static final int TURNAROUND_MINUTES=20, FIRST_SHOW_MINUTE=9*60, LAST_START_MINUTE=23*60+30;
    static final int FILL_SITES_PER_BATCH=3, PURGE_SHOWS_PER_BATCH=60;
    private final BookingStore store;
    private final AdmissionGate gate;
    private final boolean enabled;
    private final int windowDays;
    private final long intervalMs;
    private ScheduledExecutorService executor;
    public record Fill(int shows,int seats) {}
    public record Purge(int shows,int seats,int bookings,int payments,int refundsRequired) {}

    public MovieScheduleMaintainer(BookingStore store,AdmissionGate gate,
            @Value("${movie.schedule-enabled:false}") boolean enabled,
            @Value("${movie.booking-window-days:3}") int windowDays,
            @Value("${movie.schedule-interval-ms:900000}") long intervalMs) {
        this.store=store;this.gate=gate;this.enabled=enabled;this.windowDays=windowDays;this.intervalMs=intervalMs;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if(!enabled) return;
        executor=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("movie-schedule").daemon().factory());
        executor.scheduleWithFixedDelay(this::tick,0,intervalMs,TimeUnit.MILLISECONDS);
    }
    @PreDestroy
    public void stop() { if(executor!=null) executor.shutdownNow(); }

    void tick() {
        if(!gate.enter()) return;
        try {
            long started=System.nanoTime();
            var purged=purgePast();
            var filled=fillWindow();
            if(purged.shows()>0 || filled.shows()>0)
                LOG.info("Movie schedule: purged {} shows/{} bookings/{} refunds-required, added {} shows/{} seats in {} ms",
                    purged.shows(),purged.bookings(),purged.refundsRequired(),filled.shows(),filled.seats(),(System.nanoTime()-started)/1_000_000);
        }
        catch(RuntimeException error) {LOG.warn("Movie schedule tick will retry: {}",error.toString());}
        finally {gate.leave();}
    }

    /** Fills every catalog site for each local date in the window; already-scheduled screen-days are skipped. */
    public Fill fillWindow() {
        int shows=0,seats=0;
        var zones=store.jdbc().queryForList("""
            SELECT DISTINCT mx.zone_id FROM movie_catalog_sites cs JOIN movie_multiplexes mx ON mx.id=cs.multiplex_id ORDER BY 1
            """,String.class);
        for(String zone:zones) {
            LocalDate today=store.jdbc().queryForObject("SELECT (clock_timestamp() AT TIME ZONE ?)::date",LocalDate.class,zone);
            var sites=store.jdbc().queryForList("""
                SELECT cs.multiplex_id FROM movie_catalog_sites cs JOIN movie_multiplexes mx ON mx.id=cs.multiplex_id
                WHERE mx.zone_id=? ORDER BY cs.multiplex_id
                """,UUID.class,zone);
            for(int day=0;day<=windowDays;day++)
                for(int from=0;from<sites.size();from+=FILL_SITES_PER_BATCH) {
                    var batch=fill(today.plusDays(day),sites.subList(from,Math.min(from+FILL_SITES_PER_BATCH,sites.size())));
                    if(batch==null) return new Fill(shows,seats);
                    shows+=batch.shows();seats+=batch.seats();
                }
        }
        return new Fill(shows,seats);
    }

    /**
     * One screen-day plays one now-showing movie, picked by weight from a stable hash of
     * screen and date; shows run back to back from a staggered 09:00-10:30 opening until
     * the last start at or before 23:30. Only future slots are inserted. Returns null when
     * another replica holds the maintainer lock.
     */
    public Fill fill(LocalDate day,List<UUID> multiplexes) {
        return store.tx(()-> {
            if(!locked()) return null;
            String ids=multiplexes.stream().map(UUID::toString).collect(Collectors.joining(",","{","}"));
            var row=store.jdbc().queryForMap("""
                WITH slate AS (
                  SELECT n.movie_id,m.duration_minutes,
                    sum(n.weight) OVER (ORDER BY n.movie_id)-n.weight AS lo,sum(n.weight) OVER (ORDER BY n.movie_id) AS hi,
                    sum(n.weight) OVER () AS total
                  FROM movie_now_showing n JOIN movies m ON m.id=n.movie_id
                ), screen_day AS (
                  SELECT s.id AS screen_id,mx.zone_id,(abs(hashtext(s.id::text)::bigint)%7)*15 AS open_offset,
                    abs(hashtext(s.id::text||'/'||?::text)::bigint) AS pick
                  FROM movie_screens s JOIN movie_multiplexes mx ON mx.id=s.multiplex_id
                  WHERE s.multiplex_id=ANY(?::uuid[]) AND NOT EXISTS (
                    SELECT 1 FROM movie_shows sh WHERE sh.screen_id=s.id
                      AND sh.starts_at>=(?::date)::timestamp AT TIME ZONE mx.zone_id
                      AND sh.starts_at<(?::date+1)::timestamp AT TIME ZONE mx.zone_id)
                ), planned AS (
                  SELECT sd.screen_id,sl.movie_id,sl.duration_minutes,
                    ((?::date)::timestamp+make_interval(mins=>?+sd.open_offset::int+k*slot.minutes)) AT TIME ZONE sd.zone_id AS starts_at
                  FROM screen_day sd JOIN slate sl ON sd.pick%sl.total>=sl.lo AND sd.pick%sl.total<sl.hi
                  CROSS JOIN LATERAL (SELECT (ceil((sl.duration_minutes+?)/5.0)*5)::int AS minutes) slot
                  CROSS JOIN LATERAL generate_series(0,(?-?-sd.open_offset::int)/slot.minutes) k
                ), new_shows AS (
                  INSERT INTO movie_shows(id,movie_id,screen_id,starts_at,ends_at,occupied_until)
                  SELECT gen_random_uuid(),movie_id,screen_id,starts_at,starts_at+make_interval(mins=>duration_minutes),
                    starts_at+make_interval(mins=>duration_minutes+?)
                  FROM planned WHERE starts_at>clock_timestamp()
                  ON CONFLICT (screen_id,starts_at) DO NOTHING
                  RETURNING id,screen_id
                ), new_seats AS (
                  INSERT INTO movie_show_seats(show_id,seat_number,category,price_minor)
                  SELECT ns.id,n,c.category,p.price_minor
                  FROM new_shows ns JOIN movie_screens s ON s.id=ns.screen_id
                  JOIN movie_catalog_sites cs ON cs.multiplex_id=s.multiplex_id
                  JOIN movie_screen_categories c ON c.screen_id=ns.screen_id
                  JOIN movie_catalog_prices p ON p.price_tier=cs.price_tier AND p.category=c.category
                  CROSS JOIN LATERAL (SELECT coalesce(sum(prior.seat_count),0)::int AS before FROM movie_screen_categories prior
                    WHERE prior.screen_id=c.screen_id AND prior.category<c.category) offset_
                  CROSS JOIN LATERAL generate_series(offset_.before+1,offset_.before+c.seat_count) n
                  RETURNING 1
                )
                SELECT (SELECT count(*) FROM new_shows) AS shows,(SELECT count(*) FROM new_seats) AS seats
                """,Date.valueOf(day),ids,Date.valueOf(day),Date.valueOf(day),Date.valueOf(day),FIRST_SHOW_MINUTE,
                TURNAROUND_MINUTES,LAST_START_MINUTE,FIRST_SHOW_MINUTE,TURNAROUND_MINUTES);
            return new Fill(((Number)row.get("shows")).intValue(),((Number)row.get("seats")).intValue());
        });
    }

    /** Deletes, batch by batch, every show before today's local date and all rows that depend on it. */
    public Purge purgePast() {
        int shows=0,seats=0,bookings=0,payments=0,refunds=0;
        while(true) {
            var batch=purgeBatch();
            if(batch==null || batch.shows()==0) return new Purge(shows,seats,bookings,payments,refunds);
            shows+=batch.shows();seats+=batch.seats();bookings+=batch.bookings();payments+=batch.payments();refunds+=batch.refundsRequired();
        }
    }

    /**
     * Shows with an open (PENDING/RETRY_PENDING) payment are left for the payment worker to
     * settle first. All deletes run in one statement: NO ACTION foreign keys are checked at
     * statement end, which also covers the booking/payment reference cycle.
     */
    Purge purgeBatch() {
        return store.tx(()-> {
            if(!locked()) return null;
            var row=store.jdbc().queryForMap("""
                WITH doomed AS (
                  SELECT sh.id FROM movie_shows sh JOIN movie_screens s ON s.id=sh.screen_id
                  JOIN movie_multiplexes mx ON mx.id=s.multiplex_id
                  WHERE sh.starts_at<(clock_timestamp() AT TIME ZONE mx.zone_id)::date::timestamp AT TIME ZONE mx.zone_id
                    AND NOT EXISTS (SELECT 1 FROM movie_bookings b JOIN movie_payments p ON p.booking_id=b.id
                      WHERE b.show_id=sh.id AND p.state IN ('PENDING','RETRY_PENDING'))
                  ORDER BY sh.starts_at,sh.id LIMIT ?
                ), bk AS (SELECT id FROM movie_bookings WHERE show_id IN (SELECT id FROM doomed)
                ), pay AS (SELECT id,refund_required FROM movie_payments WHERE booking_id IN (SELECT id FROM bk)
                ), receipts AS (DELETE FROM movie_provider_receipts WHERE payment_id IN (SELECT id FROM pay)
                ), payments AS (DELETE FROM movie_payments WHERE id IN (SELECT id FROM pay) RETURNING refund_required
                ), audit AS (DELETE FROM movie_booking_audit WHERE booking_id IN (SELECT id FROM bk)
                ), booking_seats AS (DELETE FROM movie_booking_seats WHERE show_id IN (SELECT id FROM doomed)
                ), show_seats AS (DELETE FROM movie_show_seats WHERE show_id IN (SELECT id FROM doomed) RETURNING 1
                ), bookings AS (DELETE FROM movie_bookings WHERE id IN (SELECT id FROM bk) RETURNING 1
                ), shows AS (DELETE FROM movie_shows WHERE id IN (SELECT id FROM doomed) RETURNING 1)
                SELECT (SELECT count(*) FROM shows) AS shows,(SELECT count(*) FROM show_seats) AS seats,
                  (SELECT count(*) FROM bookings) AS bookings,(SELECT count(*) FROM payments) AS payments,
                  (SELECT count(*) FROM payments WHERE refund_required) AS refunds
                """,PURGE_SHOWS_PER_BATCH);
            var purge=new Purge(((Number)row.get("shows")).intValue(),((Number)row.get("seats")).intValue(),
                ((Number)row.get("bookings")).intValue(),((Number)row.get("payments")).intValue(),((Number)row.get("refunds")).intValue());
            if(purge.shows()>0)
                store.jdbc().update("INSERT INTO movie_purge_log(shows,seats,bookings,payments,refunds_required) VALUES (?,?,?,?,?)",
                    purge.shows(),purge.seats(),purge.bookings(),purge.payments(),purge.refundsRequired());
            return purge;
        });
    }

    private boolean locked() {
        return Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT pg_try_advisory_xact_lock(?)",Boolean.class,LOCK_KEY));
    }
}

package com.example.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import static com.example.booking.MovieModels.*;

@Service
public class MoviePaymentService {
    private static final Logger LOG=LoggerFactory.getLogger(MoviePaymentService.class);
    private final MovieStore store;
    private final MoviePaymentMock provider;
    private final MovieBookingService bookings;
    private final AdmissionGate gate;
    private final boolean controls,worker,maintenance;
    public MoviePaymentService(MovieStore store,MoviePaymentMock provider,MovieBookingService bookings,AdmissionGate gate,
            @Value("${movie.payment-controls-enabled:false}") boolean controls,
            @Value("${movie.payment-worker-enabled:true}") boolean worker,
            @Value("${lab.maintenance-enabled:true}") boolean maintenance) {
        this.store=store;this.provider=provider;this.bookings=bookings;this.gate=gate;
        this.controls=controls;this.worker=worker;this.maintenance=maintenance;
    }
    public Checkout checkout(UUID booking,String key,CheckoutRequest r) {
        BookingService.validateKey(key);
        if(r.testBucket()!=null && !controls) throw new ApiException(400,"PAYMENT_FIXTURES_DISABLED","testBucket is a disabled local study control");
        return store.db().tx(()-> {
            store.locked(booking);
            var existing=store.db().jdbc().query("SELECT * FROM movie_payments WHERE booking_id=? AND checkout_key=?",MovieStore.PAYMENT,booking,key);
            if(!existing.isEmpty()) {
                var p=existing.getFirst();
                if(p.delayMs()!=r.delayMs() || !Objects.equals(p.requestedTestBucket(),r.testBucket()))
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT","Payment key already binds different input");
                store.expireLocked(booking); return new Checkout(store.view(booking,true),p.payment(),true);
            }
            store.expireLocked(booking);
            var b=store.row(booking);
            if(!b.state().equals("HELD")) throw ApiException.conflict("HOLD_NOT_VALID","Fresh payment requires HELD seats; another payment may be pending");
            if(!store.ownsAll(booking)) throw ApiException.conflict("SEAT_OWNERSHIP_LOST","Selected seats no longer belong to this hold");
            UUID id=UUID.randomUUID(); int bucket=r.testBucket()==null?MoviePaymentMock.bucket(id):r.testBucket();
            store.db().jdbc().update("""
                INSERT INTO movie_payments(id,booking_id,checkout_key,payment_number,amount_minor,plan_bucket,requested_test_bucket,delay_ms,next_at)
                SELECT ?,?, ?,COALESCE(max(payment_number),0)+1,?,?,?,?,clock_timestamp()+(? * interval '1 millisecond')
                FROM movie_payments WHERE booking_id=?
                """,id,booking,key,b.amountMinor(),bucket,r.testBucket(),r.delayMs(),r.delayMs(),booking);
            int changed=store.db().jdbc().update("UPDATE movie_bookings SET state='PAYMENT_PENDING',current_payment_id=? WHERE id=? AND state='HELD' AND expires_at>clock_timestamp()",id,booking);
            if(changed!=1) throw ApiException.conflict("HOLD_NOT_VALID","Hold expired before payment admission");
            store.audit(booking,"PAYMENT_STARTED"); return new Checkout(store.view(booking,false),store.payment(id).payment(),false);
        });
    }
    public Payment get(UUID id) { return store.payment(id).payment(); }
    public List<Payment> history(UUID booking) {
        store.row(booking);
        return store.db().jdbc().query("SELECT * FROM movie_payments WHERE booking_id=? ORDER BY payment_number",MovieStore.PAYMENT,booking).stream().map(PaymentRow::payment).toList();
    }
    public Booking reconcile(UUID id) { recover(id,true);return bookings.get(get(id).bookingId()); }
    public Booking manualReconcile(UUID id) {
        if(!controls) throw ApiException.missing();
        return reconcile(id);
    }
    void recover(UUID id,boolean force) {
        UUID token=UUID.randomUUID();
        var claimed=store.db().tx(()->store.db().jdbc().query("""
            UPDATE movie_payments SET lease_token=?,lease_until=clock_timestamp()+interval '5 seconds',dispatches=dispatches+1
            WHERE id=? AND state IN ('PENDING','RETRY_PENDING') AND (? OR next_at<=clock_timestamp())
              AND (lease_until IS NULL OR lease_until<=clock_timestamp()) RETURNING *
            """,MovieStore.PAYMENT,token,id,force));
        if(claimed.isEmpty()) return;
        var p=claimed.getFirst();
        String outcome=provider.accept(id,p.planBucket(),p.payment().providerStep());
        if(outcome.equals("RETRYABLE_FAILURE")) {
            store.db().tx(()-> {
                store.db().jdbc().update("""
                    UPDATE movie_payments SET state='RETRY_PENDING',provider_step=2,next_at=clock_timestamp()+interval '500 milliseconds',
                      lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=? AND lease_until>clock_timestamp()
                      AND state='PENDING' AND provider_step=1
                    """,id,token);
                return null;
            });
            return;
        }
        apply(id,token,outcome);
    }
    void apply(UUID id,UUID token,String outcome) {
        UUID booking=get(id).bookingId();
        store.db().tx(()-> {
            store.locked(booking);
            var p=BookingStore.one(store.db().jdbc().query("SELECT * FROM movie_payments WHERE id=? FOR UPDATE",MovieStore.PAYMENT,id));
            if(!token.equals(p.leaseToken()) || !Boolean.TRUE.equals(store.db().jdbc().queryForObject("SELECT lease_until>clock_timestamp() FROM movie_payments WHERE id=?",Boolean.class,id))) return null;
            String receipt=store.db().jdbc().queryForObject("SELECT outcome FROM movie_provider_receipts WHERE payment_id=? AND provider_step=?",String.class,id,p.payment().providerStep());
            if(!outcome.equals(receipt) || !Set.of("SUCCESS","FAILURE").contains(outcome)) throw new IllegalStateException("Unverified provider outcome");
            store.expireLocked(booking);
            boolean success=outcome.equals("SUCCESS");
            store.db().jdbc().update("UPDATE movie_payments SET state=?,lease_token=NULL,lease_until=NULL WHERE id=?",success?"SUCCEEDED":"FAILED",id);
            var b=store.row(booking);
            int changed=0;
            if(id.equals(b.currentPaymentId()) && store.ownsAll(booking)) {
                changed=store.db().jdbc().update("UPDATE movie_bookings SET state=? WHERE id=? AND state='PAYMENT_PENDING' AND expires_at>clock_timestamp()",
                    success?"CONFIRMED":"HELD",booking);
            }
            if(success && changed==0) {
                store.expireLocked(booking);
                store.db().jdbc().update("UPDATE movie_payments SET refund_required=true WHERE id=?",id);
                store.audit(booking,"LATE_SUCCESS_REFUND_REQUIRED");
            } else store.audit(booking,success?"CONFIRMED":changed==1?"PAYMENT_FAILED_HOLD_RETAINED":"PAYMENT_FAILED_AFTER_RELEASE");
            return null;
        });
    }
    @Scheduled(fixedDelayString="${movie.payment-tick-ms:200}")
    public void tick() {
        if(!worker || !maintenance || !gate.enter()) return;
        try {
            bookings.expireBatch();
            var ids=store.db().jdbc().queryForList("SELECT id FROM movie_payments WHERE state IN ('PENDING','RETRY_PENDING') AND next_at<=clock_timestamp() AND (lease_until IS NULL OR lease_until<=clock_timestamp()) ORDER BY next_at,id LIMIT 20",UUID.class);
            for(UUID id:ids) recover(id,false);
        } catch(RuntimeException error) { LOG.warn("Movie maintenance will retry persistent work: {}",error.getClass().getSimpleName()); }
        finally { gate.leave(); }
    }
}

package com.example.booking;

import org.springframework.stereotype.Service;
import java.util.*;
import static com.example.booking.Models.*;

@Service
public class PaymentProcessor {
    private final BookingStore store;
    private final LocalProvider provider;
    private final RecoveryIsolation isolation;
    public PaymentProcessor(BookingStore store,LocalProvider provider,RecoveryIsolation isolation) {this.store=store;this.provider=provider;this.isolation=isolation;}
    public Payment get(UUID id) {return store.paymentRow(id).payment();}
    public Map<String,Object> recoveryStatus(UUID id) {
        get(id);
        return store.jdbc().queryForMap("""
            SELECT id, state, attempts, retry_started_at AS "retryStartedAt",retry_exhausted AS "retryExhausted",
              last_error AS "lastError",next_at AS "nextAt",lease_until AS "leaseUntil",
              item_failures AS "itemFailures",quarantined_at AS "quarantinedAt",
              quarantine_reason AS "quarantineReason",redrive_count AS "redriveCount"
            FROM payments WHERE id=?
            """,id);
    }
    public Booking reconcile(UUID id) {
        if(recoveryStatus(id).get("quarantinedAt")!=null)
            throw ApiException.conflict("PAYMENT_QUARANTINED","Investigate and use a keyed controlled redrive");
        recover(id,true);
        UUID booking=get(id).bookingId();
        return store.tx(() -> {store.locked(booking);store.expire(booking);return store.view(booking,false);});
    }
    public Map<String,Object> redrive(UUID id,String key) {
        boolean admitted=recover(id,true,key);
        return Map.of("replayed",!admitted,"recovery",recoveryStatus(id));
    }
    public int recoverBatch() {
        if(provider.remote()) store.jdbc().update("""
            UPDATE payments SET retry_exhausted=true WHERE state IN ('PENDING','UNKNOWN') AND NOT retry_exhausted
              AND (attempts>=4 OR retry_started_at<=clock_timestamp()-interval '10 seconds')
              AND (lease_until IS NULL OR lease_until<=clock_timestamp())
            """);
        var ids=store.jdbc().queryForList("""
            SELECT id FROM payments WHERE state IN ('PENDING','UNKNOWN') AND NOT retry_exhausted AND quarantined_at IS NULL AND next_at <= clock_timestamp()
              AND (lease_until IS NULL OR lease_until <= clock_timestamp()) ORDER BY next_at,id LIMIT 20
            """,UUID.class);
        for(UUID id:ids) recover(id,false);
        return ids.size();
    }
    void recover(UUID id,boolean force) {
        recover(id,force,null);
    }
    boolean recover(UUID id,boolean force,String redriveKey) {
        UUID token=UUID.randomUUID();
        var claimed=store.tx(() -> {
            if(redriveKey!=null && !isolation.admitRedrive(id,redriveKey)) return List.<PaymentRow>of();
            var rows=store.jdbc().query("""
            UPDATE payments SET lease_token=?, lease_until=clock_timestamp()+interval '5 seconds',
              attempts=attempts+1,retry_started_at=COALESCE(retry_started_at,clock_timestamp()),updated_at=clock_timestamp()
            WHERE id=? AND state IN ('PENDING','UNKNOWN') AND (? OR (NOT retry_exhausted AND next_at <= clock_timestamp()))
              AND (? OR quarantined_at IS NULL)
              AND (lease_until IS NULL OR lease_until <= clock_timestamp())
              AND (? OR NOT ? OR (attempts<4 AND (retry_started_at IS NULL OR retry_started_at>clock_timestamp()-interval '10 seconds'))) RETURNING *
            """,BookingStore.PAYMENT,token,id,force,redriveKey!=null,force,provider.remote());
            if(!rows.isEmpty()) store.jdbc().update("INSERT INTO recovery_history(payment_id,action,lease_token) VALUES (?,'DISPATCH_CLAIMED',?)",id,token);
            return rows;
        });
        if(claimed.isEmpty()) return false;
        Payment p=claimed.getFirst().payment();
        // No inventory transaction/lock is alive here. A crash after acceptance
        // leaves an idempotent provider receipt and a recoverable leased intent.
        try {
        Receipt receipt=provider.accept(p);
        isolation.afterAcceptance(id);
        if(!receipt.ready() || (p.scenario()==Scenario.UNKNOWN && p.attempts()==1)) {
            if(provider.remote()) {defer(id,token,"OUTCOME_PENDING",p.attempts());return true;}
            store.tx(() -> {
                store.jdbc().update("""
                    UPDATE payments SET state='UNKNOWN',next_at=GREATEST(ready_at,clock_timestamp()+interval '3 seconds'),
                      lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp()
                    WHERE id=? AND lease_token=? AND state IN ('PENDING','UNKNOWN')
                    """,id,token);
                return null;
            });
            return true;
        }
        apply(id,"provider/"+id,receipt.outcome(),token);
        }
        catch(ProviderBoundary.Unavailable error) {
            defer(id,token,error.getMessage(),p.attempts());
        }
        catch(org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException dependency) {
            // A shared DB failure aborts this batch; it must never mark the item malformed.
            throw dependency;
        }
        catch(RuntimeException itemError) {isolation.failed(id,token,itemError);}
        return true;
    }
    void defer(UUID id,UUID token,String reason,int attempts) {
        // Capped exponential backoff with bounded jitter; only this durable worker owns retries. No sleep holds a DB connection.
        long cap=Math.min(2000,500L << Math.min(3,Math.max(0,attempts-1)));
        long delay=java.util.concurrent.ThreadLocalRandom.current().nextLong(100,cap+1);
        store.tx(() -> {
            store.jdbc().update("""
                UPDATE payments SET state='UNKNOWN',last_error=?,
                  retry_exhausted=(attempts>=4 OR retry_started_at <= clock_timestamp()-interval '10 seconds'),
                  next_at=clock_timestamp()+(? * interval '1 millisecond'),
                  lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp()
                WHERE id=? AND lease_token=? AND state IN ('PENDING','UNKNOWN')
                """,reason,delay,id,token);
            return null;
        });
    }
    public Booking callback(UUID id,CallbackRequest request) {
        return apply(id,request.eventId(),request.outcome(),null);
    }
    Booking apply(UUID id,String eventId,Outcome outcome,UUID token) {
        UUID booking=get(id).bookingId();
        // Receipt is an immutable local observation; no HTTP call under inventory locks.
        Receipt observed=provider.lookup(id);
        return store.tx(() -> {
            store.locked(booking);
            PaymentRow p=BookingStore.one(store.jdbc().query("SELECT * FROM payments WHERE id=? FOR UPDATE",BookingStore.PAYMENT,id));
            if(token!=null && (!token.equals(p.leaseToken()) || !Boolean.TRUE.equals(store.jdbc().queryForObject(
                "SELECT lease_until > clock_timestamp() FROM payments WHERE id=?",Boolean.class,id))))
                return store.view(booking,true);
            Receipt receipt=observed;
            if(!receipt.ready()) throw ApiException.conflict("PROVIDER_PENDING","Simulated outcome is not yet available");
            if(receipt.outcome()!=outcome) throw ApiException.conflict("OUTCOME_CONFLICT","Callback disagrees with the immutable local provider receipt");
            int inserted=store.jdbc().update("INSERT INTO provider_events(event_id,payment_id,outcome) VALUES (?,?,?) ON CONFLICT DO NOTHING",eventId,id,outcome.name());
            var event=store.jdbc().queryForMap("SELECT payment_id,outcome FROM provider_events WHERE event_id=?",eventId);
            if(!id.equals(event.get("payment_id")) || !outcome.name().equals(event.get("outcome")))
                throw ApiException.conflict("EVENT_CONFLICT","Event ID was already used for another payment/outcome");
            store.expire(booking);
            boolean terminal=Set.of("SUCCESS","FAILURE").contains(p.payment().state());
            if(!terminal) {
                store.jdbc().update("UPDATE payments SET state=?,quarantined_at=NULL,lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE id=?",outcome.name(),id);
                store.jdbc().update("INSERT INTO recovery_history(payment_id,action) VALUES (?,'OUTCOME_APPLIED')",id);
                if(outcome==Outcome.SUCCESS) {
                    int confirmed=store.jdbc().update("""
                        UPDATE bookings SET state='CONFIRMED',delivery_version=delivery_version+1,updated_at=clock_timestamp()
                        WHERE id=? AND state IN ('HELD','CHECKOUT') AND expires_at > clock_timestamp()
                        """,booking);
                    if(confirmed==1) {
                        store.audit(booking,"CONFIRMED");
                        store.enqueueSnapshot(booking,"CONFIRMED");
                    }
                    else {
                        store.expire(booking);
                        store.jdbc().update("UPDATE bookings SET reconciliation='REFUND_REQUIRED',updated_at=clock_timestamp() WHERE id=?",booking);
                        store.audit(booking,"LATE_SUCCESS_REFUND_REQUIRED");
                    }
                } else {
                    if(store.jdbc().update("UPDATE bookings SET state='PAYMENT_FAILED',updated_at=clock_timestamp() WHERE id=? AND state IN ('HELD','CHECKOUT')",booking)==1)
                        store.audit(booking,"PAYMENT_FAILED");
                }
                store.audit(booking,"PAYMENT_"+outcome.name());
            }
            return store.view(booking,inserted==0 || terminal);
        });
    }
}

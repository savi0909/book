package com.example.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/** Durable per-item isolation; dependency failures retain their separate retry policy. */
@Component
public class RecoveryIsolation {
    private final BookingStore store;
    private final boolean fixturesEnabled;
    public RecoveryIsolation(BookingStore store,@Value("${lab.poison-controls-enabled:false}") boolean enabled) {
        this.store=store;this.fixturesEnabled=enabled;
    }
    void afterAcceptance(UUID id) {
        if(fixturesEnabled && Boolean.TRUE.equals(store.jdbc().queryForObject(
            "SELECT poison_fixture FROM payments WHERE id=?",Boolean.class,id)))
            throw new PoisonFixtureException();
    }
    static class PoisonFixtureException extends RuntimeException { }
    public Map<String,Object> fixture(UUID id,boolean enabled) {
        store.tx(() -> {
            var rows=store.jdbc().queryForList("SELECT * FROM payments WHERE id=? FOR UPDATE",id);
            if(rows.isEmpty()) throw ApiException.missing();
            var row=rows.getFirst();
            if(row.get("lease_until")!=null && Boolean.TRUE.equals(store.jdbc().queryForObject(
                "SELECT lease_until>clock_timestamp() FROM payments WHERE id=?",Boolean.class,id)))
                throw ApiException.conflict("RECOVERY_LEASED","Wait for the active recovery lease");
            if(!Set.of("PENDING","UNKNOWN").contains(row.get("state")))
                throw ApiException.conflict("PAYMENT_TERMINAL","Fixture requires an unresolved payment");
            store.jdbc().update("UPDATE payments SET poison_fixture=? WHERE id=?",enabled,id);
            store.jdbc().update("INSERT INTO recovery_history(payment_id,action) VALUES (?,?)",id,enabled?"FIXTURE_ENABLED":"FIXTURE_FIXED");
            return null;
        });
        return Map.of("paymentId",id,"enabled",enabled);
    }
    // Called inside the same short transaction as the lease claim. Row lock serializes replicas.
    boolean admitRedrive(UUID id,String key) {
        BookingService.validateKey(key);
        var row=store.jdbc().queryForList("SELECT * FROM payments WHERE id=? FOR UPDATE",id);
        if(row.isEmpty()) throw ApiException.missing();
        if(!store.jdbc().queryForList("SELECT id FROM recovery_history WHERE payment_id=? AND request_key=?",id,key).isEmpty()) return false;
        if(row.getFirst().get("quarantined_at")==null)
            throw ApiException.conflict("NOT_QUARANTINED","Redrive requires quarantine; use reconcile for dependency exhaustion");
        if(((Number)row.getFirst().get("redrive_count")).intValue()>=2)
            throw ApiException.conflict("REDRIVE_EXHAUSTED","Two deliberate redrives have already been admitted");
        if(Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT lease_until>clock_timestamp() FROM payments WHERE id=?",Boolean.class,id)))
            throw ApiException.conflict("RECOVERY_LEASED","Wait for the active recovery lease");
        store.jdbc().update("UPDATE payments SET redrive_count=redrive_count+1 WHERE id=?",id);
        store.jdbc().update("INSERT INTO recovery_history(payment_id,action,request_key) VALUES (?,'REDRIVE_REQUESTED',?)",id,key);
        return true;
    }
    void failed(UUID id,UUID token,RuntimeException error) {
        // Error class only: never persist request bodies, arbitrary exception text or secrets.
        String reason=error.getClass().getSimpleName();
        if(reason.length()>64) reason=reason.substring(0,64);
        String safeReason=reason;
        store.tx(() -> {
            var changed=store.jdbc().queryForList("""
                UPDATE payments SET state='UNKNOWN',item_failures=LEAST(3,item_failures+1),
                  quarantined_at=CASE WHEN item_failures>=2 OR quarantined_at IS NOT NULL
                    THEN COALESCE(quarantined_at,clock_timestamp()) ELSE NULL END,
                  quarantine_reason=?,next_at=clock_timestamp()+interval '1 second',
                  lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp()
                WHERE id=? AND lease_token=? AND lease_until>clock_timestamp() AND state IN ('PENDING','UNKNOWN')
                RETURNING quarantined_at
                """,safeReason,id,token);
            if(!changed.isEmpty()) store.jdbc().update("""
                INSERT INTO recovery_history(payment_id,action,reason,lease_token) VALUES (?,?,?,?)
                """,id,changed.getFirst().get("quarantined_at")==null?"ITEM_FAILED":"QUARANTINED",safeReason,token);
            return null;
        });
    }
    public List<Map<String,Object>> history(UUID id) {
        store.paymentRow(id);
        return store.jdbc().queryForList("SELECT id,action,reason,request_key AS \"requestKey\",created_at AS \"createdAt\" FROM recovery_history WHERE payment_id=? ORDER BY id DESC LIMIT 100",id);
    }
    public Map<String,Object> summary() {
        return store.jdbc().queryForMap("""
            SELECT count(*) AS quarantined,
              COALESCE(EXTRACT(EPOCH FROM clock_timestamp()-min(quarantined_at)),0) AS "oldestSeconds",
              COALESCE(sum(redrive_count),0) AS "redrives"
            FROM payments WHERE quarantined_at IS NOT NULL AND state IN ('PENDING','UNKNOWN')
            """);
    }
}

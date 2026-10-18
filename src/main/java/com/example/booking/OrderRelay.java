package com.example.booking;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import static com.example.booking.OrderMessages.*;

@Service
public class OrderRelay {
    public interface Transport {void publish(String topic,Envelope message) throws Exception;}
    public record Claim(UUID id,UUID token,int attempts,String destination,String payload) {}
    private final OrderStore store;
    private final ObjectProvider<Transport> transport;
    private final int batch,concurrency,leaseSeconds,maxClaims,backoffBase,backoffCap;
    private final String worker=UUID.randomUUID().toString();
    public OrderRelay(OrderStore store,ObjectProvider<Transport> transport,
        @Value("${order.batch-size:20}") int batch,@Value("${order.publish-concurrency:10}") int concurrency,
        @Value("${order.lease-seconds:30}") int lease,@Value("${order.max-claims:10}") int maxClaims,
        @Value("${order.backoff-base-ms:1000}") int base,@Value("${order.backoff-cap-ms:300000}") int cap,
        @Value("${order.broker-timeout-ms:5000}") int brokerTimeout) {
        this.store=store;this.transport=transport;this.batch=batch;this.concurrency=concurrency;this.leaseSeconds=lease;
        this.maxClaims=maxClaims;this.backoffBase=base;this.backoffCap=cap;
        if(batch<1 || batch>100 || concurrency<1 || concurrency>32 || lease*1000L<brokerTimeout*2L+5000 ||
            maxClaims<1 || base<1 || cap<base)throw new IllegalArgumentException("Invalid relay bounds");
    }
    public List<Claim> claim(int capacity) {
        int count=Math.min(Math.min(batch,concurrency),capacity);if(count<1)return List.of();
        UUID token=UUID.randomUUID();
        return store.db.tx(()->store.db.jdbc().query("""
            WITH picked AS (
              SELECT id FROM order_outbox WHERE status='PENDING' AND available_at<=clock_timestamp() AND attempts<?
              ORDER BY available_at,id LIMIT ? FOR UPDATE SKIP LOCKED
            ) UPDATE order_outbox o SET status='IN_PROGRESS',attempts=o.attempts+1,locked_by=?,lock_token=?,
              locked_until=clock_timestamp()+(?*interval '1 second'),updated_at=clock_timestamp()
            FROM picked p WHERE o.id=p.id RETURNING o.*
            """,(rs,n)->new Claim(rs.getObject("id",UUID.class),rs.getObject("lock_token",UUID.class),rs.getInt("attempts"),rs.getString("destination"),rs.getString("payload")),
            maxClaims,count,worker,token,leaseSeconds));
    }
    public int recoverExpired() {
        return store.db.tx(()->store.db.jdbc().update("""
            WITH picked AS (
              SELECT id FROM order_outbox WHERE status='IN_PROGRESS' AND locked_until<=clock_timestamp()
              ORDER BY locked_until,id LIMIT 100 FOR UPDATE SKIP LOCKED
            ) UPDATE order_outbox o SET status=CASE WHEN attempts>=? THEN 'DEAD' ELSE 'PENDING' END,
              available_at=clock_timestamp(),locked_by=NULL,lock_token=NULL,locked_until=NULL,
              updated_at=clock_timestamp(),last_error='lease expired; delivery unknown'
            FROM picked p WHERE o.id=p.id
            """,maxClaims));
    }
    public boolean acknowledge(Claim c) {
        return store.db.jdbc().update("""
            UPDATE order_outbox SET status='PUBLISHED',published_at=clock_timestamp(),updated_at=clock_timestamp(),
              locked_by=NULL,lock_token=NULL,locked_until=NULL,last_error=NULL
            WHERE id=? AND status='IN_PROGRESS' AND lock_token=? AND locked_until>clock_timestamp()
            """,c.id(),c.token())==1;
    }
    public boolean heartbeat(Claim c) {
        return store.db.jdbc().update("""
            UPDATE order_outbox SET locked_until=clock_timestamp()+(?*interval '1 second'),updated_at=clock_timestamp()
            WHERE id=? AND status='IN_PROGRESS' AND lock_token=? AND locked_until>clock_timestamp()
            """,leaseSeconds,c.id(),c.token())==1;
    }
    public boolean fail(Claim c,boolean permanent,String error) {
        long upper=Math.min(backoffCap,(long)backoffBase*(1L<<Math.min(30,c.attempts()-1)));
        long jitter=ThreadLocalRandom.current().nextLong(upper+1);
        return store.db.jdbc().update("""
            UPDATE order_outbox SET status=?,available_at=clock_timestamp()+(?*interval '1 millisecond'),
              locked_by=NULL,lock_token=NULL,locked_until=NULL,updated_at=clock_timestamp(),last_error=?
            WHERE id=? AND status='IN_PROGRESS' AND lock_token=? AND locked_until>clock_timestamp()
            """,permanent || c.attempts()>=maxClaims?"DEAD":"PENDING",jitter,error.substring(0,Math.min(120,error.length())),c.id(),c.token())==1;
    }
    public synchronized int dispatch() {
        recoverExpired();var claims=claim(concurrency);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs=new ArrayList<Future<?>>();
            for(var c:claims)jobs.add(pool.submit(()->{
                try {
                    if(!TOPICS.contains(c.destination()))throw new IllegalArgumentException("Unknown destination");
                    var e=store.decode(c.payload());
                    var sender=transport.getIfAvailable();if(sender==null)throw new IllegalStateException("Kafka transport disabled");
                    sender.publish(c.destination(),e);acknowledge(c);
                } catch(IllegalArgumentException permanent) {fail(c,true,"invalid envelope or destination");}
                catch(Exception transientFailure) {fail(c,false,transientFailure.getClass().getSimpleName());}
            }));
            for(var job:jobs)try{job.get();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
                catch(ExecutionException failure){throw new IllegalStateException("Relay persistence failed",failure.getCause());}
        }
        return claims.size();
    }
    public boolean replay(UUID id,String actor,String reason) {
        if(actor==null || actor.isBlank() || reason==null || reason.isBlank())throw new IllegalArgumentException("Audited actor and reason required");
        return store.db.tx(()->{
            var rows=store.db.jdbc().queryForList("SELECT aggregate_id,attempts FROM order_outbox WHERE id=? AND status='DEAD' FOR UPDATE",id);
            if(rows.isEmpty())return false;
            var row=rows.getFirst();
            store.db.jdbc().update("INSERT INTO order_recovery_audit(order_id,event_id,actor,reason,prior_attempts) VALUES (?,?,?,?,?)",row.get("aggregate_id"),id,actor,reason,row.get("attempts"));
            store.db.jdbc().update("UPDATE order_outbox SET status='PENDING',attempts=0,available_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=?",id);return true;
        });
    }
}

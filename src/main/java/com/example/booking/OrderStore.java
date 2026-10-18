package com.example.booking;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.example.booking.OrderMessages.*;

@Component
public class OrderStore {
    final BookingStore db;
    final ObjectMapper json;
    public OrderStore(BookingStore db,ObjectMapper json) {this.db=db;this.json=json;}
    String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid JSON",e);}
    }
    JsonNode node(String value) {
        try{return json.readTree(value);}catch(Exception e){throw new IllegalArgumentException("Invalid JSON",e);}
    }
    Envelope decode(String value) {
        try{var e=json.readValue(value,Envelope.class);e.validate();return e;}catch(Exception e){throw new IllegalArgumentException("Invalid envelope",e);}
    }
    static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    Instant now() {return db.jdbc().queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();}
    void advisory(String key) {db.jdbc().queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",key);}
    Map<String,Object> order(UUID id,boolean lock) {
        var rows=db.jdbc().queryForList("SELECT * FROM orders WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty())throw ApiException.missing();return rows.getFirst();
    }
    Map<String,Object> saga(UUID order,boolean lock) {
        return db.jdbc().queryForMap("SELECT * FROM order_sagas WHERE order_id=?"+(lock?" FOR UPDATE":""),order);
    }
    JsonNode snapshot(Map<String,Object> order,Map<String,Object> saga) {
        var n=json.createObjectNode();
        n.put("showId",order.get("show_id").toString());n.set("seatNumbers",node(order.get("items").toString()));
        n.put("customerId",order.get("customer_id").toString());n.put("amountMinor",((Number)order.get("amount_minor")).longValue());
        n.put("currency",order.get("currency").toString());n.put("reserveCommandId",saga.get("reserve_command_id").toString());
        n.put("captureCommandId",saga.get("capture_command_id").toString());return n;
    }
    void outbox(Envelope e,String destination,String dedupe) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Outbox requires transaction");
        e.validate();
        db.jdbc().update("""
            INSERT INTO order_outbox(id,aggregate_id,saga_id,dedupe_key,event_type,destination,payload)
            VALUES (?,?,?,?,?,?,?::jsonb) ON CONFLICT(dedupe_key) DO NOTHING
            """,e.messageId(),e.orderId(),e.sagaId(),dedupe,e.type(),destination,encode(e));
    }
    void command(Map<String,Object> o,Map<String,Object> s,String type,String suffix,UUID cause) {
        UUID saga=(UUID)s.get("id"), id=identity(saga,suffix);
        var p=snapshot(o,s);
        String destination=Set.of("ReserveInventory","CommitReservation","ReleaseReservation","CancelBooking","QueryInventory").contains(type)?INVENTORY:PAYMENT;
        db.jdbc().update("INSERT INTO order_commands(id,order_id,saga_id,type,payload) VALUES (?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
            id,o.get("id"),saga,type,encode(p));
        outbox(new Envelope(identity(id,"message"),type,1,(UUID)o.get("id"),saga,id,saga,cause,now(),p),destination,"command/"+id);
    }
    void event(Map<String,Object> o,Map<String,Object> s,String type) {
        UUID saga=(UUID)s.get("id"),id=identity(saga,type);
        outbox(new Envelope(id,type,1,(UUID)o.get("id"),saga,id,saga,null,now(),snapshot(o,s)),EVENTS,"event/"+id);
    }
    boolean inbox(String consumer,Envelope e) {
        String hash=hash(encode(e));
        int count=db.jdbc().update("INSERT INTO order_inbox(consumer_name,message_id,payload_hash) VALUES (?,?,?) ON CONFLICT DO NOTHING",consumer,e.messageId(),hash);
        if(count==0 && !hash.equals(db.jdbc().queryForObject("SELECT payload_hash FROM order_inbox WHERE consumer_name=? AND message_id=?",String.class,consumer,e.messageId())))
            throw new IllegalArgumentException("Message identity reused with changed payload");
        return count==1;
    }
    void validateCommand(Envelope e) {
        var c=db.jdbc().queryForMap("SELECT * FROM order_commands WHERE id=?",e.commandId());
        if(!e.orderId().equals(c.get("order_id")) || !e.sagaId().equals(c.get("saga_id")) || !e.type().equals(c.get("type")) ||
            !e.payload().equals(node(c.get("payload").toString())))throw new IllegalArgumentException("Command identity conflict");
    }
    void reply(Envelope command,String type,JsonNode payload) {
        UUID id=identity(command.messageId(),"reply");
        outbox(new Envelope(id,type,1,command.orderId(),command.sagaId(),command.commandId(),command.correlationId(),command.messageId(),now(),payload),REPLIES,"reply/"+id);
    }
    List<Integer> seats(JsonNode p) {
        var result=new ArrayList<Integer>();p.path("seatNumbers").forEach(n->result.add(n.asInt()));return result;
    }
}

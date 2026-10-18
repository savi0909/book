package com.example.booking;

import org.springframework.stereotype.Service;
import java.util.*;
import static com.example.booking.OrderMessages.*;

@Service
public class OrderInventory {
    private final OrderStore store;
    private final MovieBookingService bookings;
    private final MovieStore movies;
    public OrderInventory(OrderStore store,MovieBookingService bookings,MovieStore movies) {this.store=store;this.bookings=bookings;this.movies=movies;}
    public void consume(Envelope e) {
        e.validate();
        if(!Set.of("ReserveInventory","CommitReservation","ReleaseReservation","CancelBooking","QueryInventory").contains(e.type()))
            throw new IllegalArgumentException("Unsupported inventory command");
        store.db.tx(()->{
            store.validateCommand(e);if(!store.inbox("inventory",e))return null;
            var p=e.payload();UUID reserve=UUID.fromString(p.path("reserveCommandId").asText());
            store.advisory("order/inventory/"+reserve);
            var rows=store.db.jdbc().queryForList("SELECT * FROM order_inventory WHERE reserve_command_id=? FOR UPDATE",reserve);
            Map<String,Object> r=rows.isEmpty()?null:rows.getFirst();
            if(e.type().equals("ReserveInventory") && r==null) {
                try {
                    var b=bookings.holdWithinTransaction(new MovieModels.HoldRequest(UUID.fromString(p.path("showId").asText()),store.seats(p),"saga-"+e.orderId(),120),reserve.toString());
                    if(b.amountMinor()!=p.path("amountMinor").asLong()) {
                        bookings.cancelWithinTransaction(b.id(),true);insert(reserve,e.orderId(),b.id(),"REJECTED",true,b.expiresAt());
                    } else insert(reserve,e.orderId(),b.id(),"HELD",false,b.expiresAt());
                } catch(ApiException rejected) {
                    if(rejected.status()!=400 && rejected.status()!=404 && rejected.status()!=409)throw rejected;
                    insert(reserve,e.orderId(),null,"REJECTED",false,null);
                }
            } else if(Set.of("ReleaseReservation","CancelBooking").contains(e.type()) && r==null) {
                // Durable tombstone: an unobserved reserve may still be queued in Kafka.
                insert(reserve,e.orderId(),null,"RELEASED",true,null);
            } else if(r!=null && r.get("booking_id")!=null) {
                UUID booking=(UUID)r.get("booking_id");
                var b=movies.locked(booking);movies.expireLocked(booking);b=movies.row(booking);
                String state=switch(b.state()){case "CONFIRMED"->"COMMITTED";case "CANCELLED"->"RELEASED";case "EXPIRED"->"EXPIRED";default->"HELD";};
                if(e.type().equals("CommitReservation") && state.equals("HELD") && !(boolean)r.get("stop_requested")) {
                    if(movies.ownsAll(booking)) {store.db.jdbc().update("UPDATE movie_bookings SET state='CONFIRMED' WHERE id=?",booking);movies.audit(booking,"SAGA_COMMITTED");state="COMMITTED";}
                    else throw new IllegalStateException("Inventory ownership mismatch");
                }
                if(e.type().equals("CancelBooking") || e.type().equals("ReleaseReservation") && !state.equals("COMMITTED")) {
                    bookings.cancelWithinTransaction(booking,true);state="RELEASED";
                }
                store.db.jdbc().update("UPDATE order_inventory SET state=?,stop_requested=stop_requested OR ?,version=version+1,updated_at=clock_timestamp() WHERE reserve_command_id=?",
                    state,Set.of("ReleaseReservation","CancelBooking").contains(e.type()),reserve);
            }
            var current=store.db.jdbc().queryForList("SELECT * FROM order_inventory WHERE reserve_command_id=?",reserve);
            var reply=store.json.createObjectNode();reply.put("state",current.isEmpty()?"UNKNOWN":current.getFirst().get("state").toString());
            if(!current.isEmpty()) {
                var row=current.getFirst();reply.put("participantVersion",((Number)row.get("version")).longValue());
                if(row.get("booking_id")!=null)reply.put("reservationId",row.get("booking_id").toString());
                if(row.get("expires_at")!=null)reply.put("expiresAt",((java.sql.Timestamp)row.get("expires_at")).toInstant().toString());
            }
            store.reply(e,"InventoryResult",reply);return null;
        });
    }
    private void insert(UUID reserve,UUID order,UUID booking,String state,boolean stop,java.time.Instant expires) {
        store.db.jdbc().update("INSERT INTO order_inventory(reserve_command_id,order_id,booking_id,state,stop_requested,expires_at) VALUES (?,?,?,?,?,?)",
            reserve,order,booking,state,stop,expires==null?null:java.sql.Timestamp.from(expires));
    }
    public int expire() {
        // The normal movie expiry worker is also authoritative; the status query above reads its durable result.
        var rows=store.db.jdbc().queryForList("SELECT reserve_command_id,booking_id FROM order_inventory WHERE state='HELD' AND expires_at<=clock_timestamp() ORDER BY expires_at,reserve_command_id LIMIT 20");
        for(var row:rows)store.db.tx(()->{
            UUID reserve=(UUID)row.get("reserve_command_id"),booking=(UUID)row.get("booking_id");
            store.advisory("order/inventory/"+reserve);
            store.db.jdbc().queryForList("SELECT reserve_command_id FROM order_inventory WHERE reserve_command_id=? FOR UPDATE",reserve);
            movies.locked(booking);movies.expireLocked(booking);
            String state=switch(movies.row(booking).state()){case "CONFIRMED"->"COMMITTED";case "CANCELLED"->"RELEASED";case "EXPIRED"->"EXPIRED";default->"HELD";};
            store.db.jdbc().update("UPDATE order_inventory SET state=?,version=version+1,updated_at=clock_timestamp() WHERE reserve_command_id=?",state,reserve);return null;
        });return rows.size();
    }
}

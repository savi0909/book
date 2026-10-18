package com.example.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.*;
import static com.example.booking.OrderMessages.*;

@Service
public class OrderService {
    private static final Logger LOG=LoggerFactory.getLogger(OrderService.class);
    private record CanonicalRequest(UUID showId,List<Integer> seats) {}
    private final OrderStore store;
    private final int deadlineSeconds,stepSeconds,maxRecovery;
    public OrderService(OrderStore store,@Value("${order.deadline-seconds:120}") int deadline,
        @Value("${order.step-seconds:10}") int step,@Value("${order.max-recovery:10}") int maxRecovery) {
        this.store=store;this.deadlineSeconds=deadline;this.stepSeconds=step;this.maxRecovery=maxRecovery;
        if(deadline<2 || step<1 || maxRecovery<1)throw new IllegalArgumentException("Invalid saga bounds");
    }
    public String create(UUID customer,String key,Create request) {
        BookingService.validateKey(key);
        var seats=request.seatNumbers().stream().sorted().toList();
        if(seats.isEmpty() || seats.size()>10 || new HashSet<>(seats).size()!=seats.size())throw new ApiException(400,"INVALID_SEATS","Choose 1–10 distinct seats");
        String canonical=store.encode(new CanonicalRequest(request.showId(),seats)),hash=OrderStore.hash(canonical);
        return store.db.tx(()->{
            // Serialize this key before allocating an order, so concurrent losers cannot leave orphan orders.
            store.advisory("order/request/"+customer+"/"+key);
            var previous=store.db.jdbc().queryForList("SELECT request_hash,response_body FROM order_requests WHERE customer_id=? AND idempotency_key=?",customer,key);
            if(!previous.isEmpty()) {
                if(!hash.equals(previous.getFirst().get("request_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","Key binds another request");
                return previous.getFirst().get("response_body").toString();
            }
            var prices=store.db.jdbc().queryForList("SELECT seat_number,price_minor FROM movie_show_seats WHERE show_id=? ORDER BY seat_number",request.showId());
            long amount=0;for(int seat:seats) {
                var price=prices.stream().filter(p->((Number)p.get("seat_number")).intValue()==seat).findFirst().orElseThrow(ApiException::missing);
                amount=Math.addExact(amount,((Number)price.get("price_minor")).longValue());
            }
            UUID order=UUID.randomUUID(),saga=UUID.randomUUID();
            store.db.jdbc().update("INSERT INTO orders(id,customer_id,status,show_id,items,amount_minor,currency) VALUES (?,?,'PENDING',?,?::jsonb,?,'INR')",order,customer,request.showId(),store.encode(seats),amount);
            store.db.jdbc().update("""
                INSERT INTO order_sagas(id,order_id,phase,reserve_command_id,capture_command_id,deadline_at,next_action_at)
                VALUES (?,?,'RESERVING',?,?,clock_timestamp()+(?*interval '1 second'),clock_timestamp()+(?*interval '1 second'))
                """,saga,order,identity(saga,"ReserveInventory"),identity(saga,"CapturePayment"),deadlineSeconds,stepSeconds);
            var o=store.order(order,false);var s=store.saga(order,false);
            store.event(o,s,"OrderCreated");store.command(o,s,"ReserveInventory","ReserveInventory",null);
            String response=store.encode(Map.of("orderId",order,"statusUrl","/api/orders/"+order));
            store.db.jdbc().update("INSERT INTO order_requests(customer_id,idempotency_key,request_hash,order_id,response_status,response_body) VALUES (?,?,?,?,202,?::jsonb)",customer,key,hash,order,response);
            // Return the stored JSON representation on the first request too.
            return store.db.jdbc().queryForObject("SELECT response_body::text FROM order_requests WHERE customer_id=? AND idempotency_key=?",String.class,customer,key);
        });
    }
    public Map<String,Object> get(UUID customer,UUID id) {
        return store.db.tx(()->{
            var o=store.order(id,false);owner(o,customer);var s=store.saga(id,false);
            var result=new LinkedHashMap<String,Object>();result.put("orderId",id);result.put("status",o.get("status"));
            result.put("amountMinor",o.get("amount_minor"));result.put("currency",o.get("currency"));result.put("seatNumbers",store.node(o.get("items").toString()));
            result.put("sagaId",s.get("id"));result.put("phase",s.get("phase"));result.put("inventoryState",s.get("inventory_state"));
            result.put("paymentState",s.get("payment_state"));result.put("nextActionAt",s.get("next_action_at"));result.put("lastError",s.get("last_error"));return result;
        });
    }
    private void owner(Map<String,Object> o,UUID customer) {if(!customer.equals(o.get("customer_id")))throw ApiException.missing();}
    public void cancel(UUID customer,UUID id) {
        store.db.tx(()->{
            var o=store.order(id,true);owner(o,customer);var s=store.saga(id,true);
            if(o.get("status").equals("CONFIRMED"))throw ApiException.conflict("CONFIRMED_CANCELLATION_UNSUPPORTED","Confirmed cancellation needs a separate ticket policy");
            if(Set.of("CANCELLED","FAILED").contains(o.get("status")))return null;
            s.put("cancel_requested",true);save(o,s);advance(o,s,null);return null;
        });
    }
    public void consume(Envelope e) {
        e.validate();
        store.db.tx(()->{
            if(!store.inbox("order",e))return null;
            var o=store.order(e.orderId(),true);var s=store.saga(e.orderId(),true);
            if(!s.get("id").equals(e.sagaId()))throw new IllegalArgumentException("Saga mismatch");
            var commands=store.db.jdbc().queryForList("SELECT type FROM order_commands WHERE id=? AND order_id=? AND saga_id=?",e.commandId(),e.orderId(),e.sagaId());
            if(commands.isEmpty())throw new IllegalArgumentException("Uncorrelated reply");
            String command=commands.getFirst().get("type").toString();
            boolean inventory=Set.of("ReserveInventory","CommitReservation","ReleaseReservation","CancelBooking","QueryInventory").contains(command);
            if(!e.type().equals(inventory?"InventoryResult":"PaymentResult"))throw new IllegalArgumentException("Reply type mismatch");
            String fact=e.payload().path("state").asText();
            Set<String> allowed=inventory?Set.of("UNKNOWN","HELD","COMMITTED","RELEASED","REJECTED","EXPIRED"):
                Set.of("UNKNOWN","PENDING","ACTION_REQUIRED","CAPTURED","DECLINED","CANCELLED","REFUNDED","MANUAL_REVIEW");
            if(!allowed.contains(fact))throw new IllegalArgumentException("Invalid participant state");
            if(inventory) {
                long version=e.payload().path("participantVersion").asLong(-1);
                if(version<((Number)s.get("inventory_version")).longValue()) {advance(o,s,e.messageId());return null;}
                s.put("inventory_version",version);
            }
            String field=inventory?"inventory_state":"payment_state",old=s.get(field).toString();
            // Monotonic facts: a delayed HELD/CAPTURED reply cannot undo a release/refund.
            boolean finalFact=inventory?Set.of("RELEASED","REJECTED","EXPIRED").contains(old):Set.of("REFUNDED","DECLINED","CANCELLED").contains(old);
            if(fact.equals("MANUAL_REVIEW"))manual(o,s,"Participant requires manual review");
            else if(!finalFact && !(fact.equals("UNKNOWN") && !old.equals("UNKNOWN")) &&
                !(old.equals(inventory?"COMMITTED":"CAPTURED") && Set.of("UNKNOWN","PENDING","ACTION_REQUIRED","HELD").contains(fact)))s.put(field,fact);
            else if(finalFact && (fact.equals("COMMITTED") || fact.equals("CAPTURED"))) {
                // Contradictory settled facts remain an explicit operator obligation.
                if(!old.equals("REFUNDED"))manual(o,s,"Contradictory late participant success");
            }
            if(e.payload().hasNonNull("reservationId"))s.put("reservation_id",UUID.fromString(e.payload().path("reservationId").asText()));
            if(e.payload().hasNonNull("expiresAt"))s.put("reservation_expires_at",java.sql.Timestamp.from(Instant.parse(e.payload().path("expiresAt").asText())));
            if(e.payload().hasNonNull("providerId"))s.put("payment_id",e.payload().path("providerId").asText());
            advance(o,s,e.messageId());return null;
        });
        LOG.debug("Order reply committed order={} saga={} message={} command={}",e.orderId(),e.sagaId(),e.messageId(),e.commandId());
    }
    private void advance(Map<String,Object> o,Map<String,Object> s,UUID cause) {
        String inventory=s.get("inventory_state").toString(),payment=s.get("payment_state").toString();
        boolean manual=s.get("phase").equals("MANUAL_REVIEW");
        boolean stop=(boolean)s.get("cancel_requested") || (boolean)s.get("failure_requested") || manual;
        if(Set.of("REJECTED","EXPIRED").contains(inventory) || payment.equals("DECLINED")) {s.put("failure_requested",true);stop=true;}
        if(!stop && s.get("phase").equals("COMPLETED")) {save(o,s);return;}
        if(stop) {
            if(!manual){s.put("phase","COMPENSATING");o.put("status","CANCELLING");}
            store.command(o,s,"CancelPayment","CancelPayment",cause);
            if(payment.equals("CAPTURED"))store.command(o,s,"RefundPayment","RefundPayment",cause);
            store.command(o,s,inventory.equals("COMMITTED")?"CancelBooking":"ReleaseReservation",inventory.equals("COMMITTED")?"CancelBooking":"ReleaseReservation",cause);
            boolean funds=Set.of("DECLINED","CANCELLED","REFUNDED").contains(payment);
            boolean clear=Set.of("RELEASED","REJECTED","EXPIRED").contains(inventory);
            if(funds && clear && !manual) {
                String terminal=(boolean)s.get("cancel_requested")?"CANCELLED":"FAILED";
                o.put("status",terminal);s.put("phase",terminal);s.put("next_action_at",null);store.event(o,s,"Order"+(terminal.equals("FAILED")?"Failed":"Cancelled"));
            } else s.put("next_action_at",manual?null:java.sql.Timestamp.from(store.now().plusSeconds(stepSeconds)));
        } else if(inventory.equals("HELD")) {
            if(payment.equals("CAPTURED")){s.put("phase","COMMITTING");store.command(o,s,"CommitReservation","CommitReservation",cause);}
            else {s.put("phase","PAYING");store.command(o,s,"CapturePayment","CapturePayment",cause);}
        } else if(inventory.equals("COMMITTED") && payment.equals("CAPTURED")) {
            o.put("status","CONFIRMED");s.put("phase","COMPLETED");s.put("next_action_at",null);store.event(o,s,"OrderConfirmed");
        }
        save(o,s);
    }
    private void save(Map<String,Object> o,Map<String,Object> s) {
        store.db.jdbc().update("UPDATE orders SET status=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",o.get("status"),o.get("id"));
        store.db.jdbc().update("""
            UPDATE order_sagas SET phase=?,inventory_state=?,payment_state=?,reservation_id=?,reservation_expires_at=?,payment_id=?,inventory_version=?,
              cancel_requested=?,failure_requested=?,next_action_at=?,last_error=?,version=version+1,updated_at=clock_timestamp() WHERE id=?
            """,s.get("phase"),s.get("inventory_state"),s.get("payment_state"),s.get("reservation_id"),s.get("reservation_expires_at"),s.get("payment_id"),s.get("inventory_version"),
            s.get("cancel_requested"),s.get("failure_requested"),s.get("next_action_at"),s.get("last_error"),s.get("id"));
    }
    private void manual(Map<String,Object> o,Map<String,Object> s,String reason) {
        LOG.warn("Scheduling manual review order={} saga={} reason={}",o.get("id"),s.get("id"),reason);
        // Manual review schedules compensation; resuming must retain that irreversible stop decision.
        s.put("failure_requested",true);
        o.put("status","MANUAL_REVIEW");s.put("phase","MANUAL_REVIEW");s.put("next_action_at",null);s.put("last_error",reason);
    }
    public int recover() {
        return store.db.tx(()->{
            // Lock order before saga everywhere, including competing recovery schedulers.
            var ids=store.db.jdbc().queryForList("""
                SELECT o.id FROM orders o JOIN order_sagas s ON s.order_id=o.id
                WHERE s.phase IN ('RESERVING','PAYING','COMMITTING','COMPENSATING') AND s.next_action_at<=clock_timestamp()
                ORDER BY s.next_action_at,s.id LIMIT 20 FOR UPDATE OF o SKIP LOCKED
                """,UUID.class);
            for(UUID id:ids) {
                var o=store.order(id,true);var s=store.saga(id,true);int attempts=((Number)s.get("recovery_attempts")).intValue()+1;
                if(attempts>maxRecovery) {manual(o,s,"Recovery budget exhausted; inspect funds and inventory");advance(o,s,null);continue;}
                if(((java.sql.Timestamp)s.get("deadline_at")).toInstant().isBefore(store.now()))s.put("failure_requested",true);
                String generation=s.get("recovery_generation").toString();
                store.command(o,s,"QueryInventory","QueryInventory/"+generation+"/"+attempts,null);
                store.command(o,s,"QueryPayment","QueryPayment/"+generation+"/"+attempts,null);
                store.db.jdbc().update("UPDATE order_sagas SET recovery_attempts=?,next_action_at=clock_timestamp()+(?*interval '1 second') WHERE id=?",attempts,stepSeconds,s.get("id"));
                s.put("next_action_at",java.sql.Timestamp.from(store.now().plusSeconds(stepSeconds)));advance(o,s,null);
            }
            return ids.size();
        });
    }
    public void resume(UUID id,String actor,String reason) {
        store.db.tx(()->{
            var o=store.order(id,true);var s=store.saga(id,true);
            if(!s.get("phase").equals("MANUAL_REVIEW"))throw ApiException.conflict("NOT_MANUAL_REVIEW","Only manual-review workflows can resume");
            store.db.jdbc().update("INSERT INTO order_recovery_audit(order_id,actor,reason,prior_attempts) VALUES (?,?,?,?)",id,actor,reason,s.get("recovery_attempts"));
            String phase=(boolean)s.get("cancel_requested") || (boolean)s.get("failure_requested")?"COMPENSATING":"RESERVING";
            store.db.jdbc().update("UPDATE orders SET status=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",phase.equals("COMPENSATING")?"CANCELLING":"PENDING",id);
            store.db.jdbc().update("UPDATE order_sagas SET phase=?,next_action_at=clock_timestamp(),recovery_attempts=0,recovery_generation=recovery_generation+1,last_error=NULL WHERE id=?",phase,s.get("id"));
            return null;
        });
    }
}

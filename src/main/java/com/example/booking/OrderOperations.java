package com.example.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/api/demo/order-saga")
@ConditionalOnExpression("${order.enabled:false} && ${order.controls-enabled:false}")
public class OrderOperations {
    public record Audit(@NotBlank @Size(max=100) String actor,@NotBlank @Size(max=500) String reason) {}
    private final OrderStore store;
    private final OrderService orders;
    private final OrderRelay relay;
    private final String token;
    public OrderOperations(OrderStore store,OrderService orders,OrderRelay relay,@Value("${order.operator-token:}") String token) {
        this.store=store;this.orders=orders;this.relay=relay;this.token=token;
    }
    private void authorize(String auth) {
        if(token.isBlank())throw new ApiException(503,"OPERATOR_AUTH_NOT_CONFIGURED","Configure operator token");
        if(!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),auth.getBytes(StandardCharsets.UTF_8)))throw new ApiException(401,"UNAUTHORIZED","Invalid operator token");
    }
    @PostMapping("/outbox/{id}/replay") public Map<String,Object> replay(@RequestHeader("Authorization") String auth,@PathVariable UUID id,@Valid @RequestBody Audit audit) {
        authorize(auth);return Map.of("replayed",relay.replay(id,audit.actor(),audit.reason()));
    }
    @PostMapping("/orders/{id}/resume") public Map<String,Object> resume(@RequestHeader("Authorization") String auth,@PathVariable UUID id,@Valid @RequestBody Audit audit) {
        authorize(auth);orders.resume(id,audit.actor(),audit.reason());return Map.of("scheduled",true);
    }
    @PostMapping("/payments/{id}/reconcile") public Map<String,Object> payment(@RequestHeader("Authorization") String auth,@PathVariable UUID id,@Valid @RequestBody Audit audit) {
        authorize(auth);
        return store.db.tx(()->{
            var rows=store.db.jdbc().queryForList("SELECT * FROM order_payment_operations WHERE id=? AND status='MANUAL_REVIEW' FOR UPDATE",id);
            if(rows.isEmpty())return Map.of("scheduled",false);
            var row=rows.getFirst();store.db.jdbc().update("INSERT INTO order_recovery_audit(order_id,event_id,actor,reason,prior_attempts) VALUES (?,?,?,?,?)",row.get("order_id"),id,audit.actor(),audit.reason(),row.get("attempts"));
            store.db.jdbc().update("UPDATE order_payment_operations SET status='UNKNOWN',attempts=0,next_action_at=clock_timestamp(),lock_token=NULL,locked_until=NULL WHERE id=?",id);
            return Map.of("scheduled",true);
        });
    }
}

package com.example.booking;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@ConditionalOnProperty(name="order.enabled",havingValue="true")
public class OrderMetrics {
    private final OrderStore store;
    public OrderMetrics(OrderStore store,MeterRegistry meters) {
        this.store=store;
        Map<String,String> sql=Map.of(
            "outbox.pending","SELECT count(*) FROM order_outbox WHERE status='PENDING'",
            "outbox.dead","SELECT count(*) FROM order_outbox WHERE status='DEAD'",
            "outbox.expired.leases","SELECT count(*) FROM order_outbox WHERE status='IN_PROGRESS' AND locked_until<=clock_timestamp()",
            "outbox.publish.errors","SELECT count(*) FROM order_outbox WHERE last_error IS NOT NULL",
            "outbox.oldest.seconds","SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp()-min(created_at)),0) FROM order_outbox WHERE status IN ('PENDING','IN_PROGRESS')",
            "sagas.overdue","SELECT count(*) FROM order_sagas WHERE phase IN ('RESERVING','PAYING','COMMITTING','COMPENSATING') AND next_action_at<=clock_timestamp()",
            "sagas.manual.review","SELECT count(*) FROM order_sagas WHERE phase='MANUAL_REVIEW'",
            "payments.unresolved","SELECT count(*) FROM order_payment_operations WHERE status IN ('PENDING','UNKNOWN','ACTION_REQUIRED','MANUAL_REVIEW')",
            "messages.quarantined","SELECT count(*) FROM order_message_failures WHERE quarantined");
        sql.forEach((name,query)->meters.gauge("booking.order."+name,this,s->s.value(query)));
    }
    private double value(String query) {try{return store.db.jdbc().queryForObject(query,Double.class);}catch(RuntimeException unavailable){return Double.NaN;}}
}

package com.example.booking;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnExpression("${order.enabled:false} && ${order.worker-enabled:true}")
public class OrderWorkers {
    private static final Logger LOG=LoggerFactory.getLogger(OrderWorkers.class);
    private final OrderRelay relay;
    private final OrderService orders;
    private final OrderInventory inventory;
    private final OrderPayment payments;
    private final ObjectProvider<OrderKafka> kafka;
    private final AdmissionGate gate;
    public OrderWorkers(OrderRelay relay,OrderService orders,OrderInventory inventory,OrderPayment payments,ObjectProvider<OrderKafka> kafka,AdmissionGate gate) {
        this.relay=relay;this.orders=orders;this.inventory=inventory;this.payments=payments;this.kafka=kafka;this.gate=gate;
    }
    @Scheduled(fixedDelayString="${order.poll-ms:500}") public void tick() {
        if(!gate.enter())return;
        try {
            orders.recover();inventory.expire();payments.tick();relay.dispatch();
            var transport=kafka.getIfAvailable();if(transport!=null)transport.poll();
        } catch(RuntimeException unavailable) {LOG.warn("Order saga tick deferred: {}",unavailable.getClass().getSimpleName());}
        finally{gate.leave();}
    }
}

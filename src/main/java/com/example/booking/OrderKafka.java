package com.example.booking;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.example.booking.OrderMessages.*;

@Component
@ConditionalOnExpression("${order.enabled:false} && ${order.kafka-enabled:true}")
public class OrderKafka implements OrderRelay.Transport {
    private static final Logger LOG=LoggerFactory.getLogger(OrderKafka.class);
    private final OrderStore store;
    private final OrderService orders;
    private final OrderInventory inventory;
    private final OrderPayment payments;
    private final String bootstrap,group;
    private final int timeout;
    private KafkaProducer<String,String> producer;
    private KafkaConsumer<String,String> consumer;
    public OrderKafka(OrderStore store,OrderService orders,OrderInventory inventory,OrderPayment payments,
        @Value("${order.kafka-bootstrap:localhost:9094}") String bootstrap,@Value("${order.kafka-group:booking-saga-v1}") String group,
        @Value("${order.broker-timeout-ms:5000}") int timeout) {
        this.store=store;this.orders=orders;this.inventory=inventory;this.payments=payments;this.bootstrap=bootstrap;this.group=group;this.timeout=timeout;
    }
    private synchronized KafkaProducer<String,String> producer() {
        if(producer==null) {
            var p=new Properties();p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap);
            p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class);p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class);
            p.put(ProducerConfig.ACKS_CONFIG,"all");p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true);
            p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,timeout);p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,timeout);
            p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,Math.max(100,timeout-100));p.put(ProducerConfig.LINGER_MS_CONFIG,0);
            producer=new KafkaProducer<>(p);
        }
        return producer;
    }
    @Override public void publish(String topic,Envelope message) throws Exception {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Kafka send inside DB transaction");
        if(!TOPICS.contains(topic))throw new IllegalArgumentException("Unknown destination");
        producer().send(new ProducerRecord<>(topic,message.orderId().toString(),store.encode(message))).get(timeout+100L,TimeUnit.MILLISECONDS);
    }
    public synchronized int poll() {
        if(consumer==null) {
            var p=new Properties();p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap);p.put(ConsumerConfig.GROUP_ID_CONFIG,group);
            p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class);
            p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
            p.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,false);p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,20);
            p.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,timeout);p.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,300000);
            consumer=new KafkaConsumer<>(p);consumer.subscribe(List.of(INVENTORY,PAYMENT,REPLIES));
        }
        var records=consumer.poll(Duration.ofMillis(100));int processed=0;
        var blocked=new HashSet<TopicPartition>();
        for(var record:records) {
            var partition=new TopicPartition(record.topic(),record.partition());if(blocked.contains(partition))continue;
            try {
                accept(record.topic(),record.key(),record.value());
            } catch(RuntimeException failure) {
                boolean quarantine;
                try {quarantine=failed(record,failure);}catch(RuntimeException databaseUnavailable){quarantine=false;}
                if(!quarantine) {consumer.seek(partition,record.offset());blocked.add(partition);continue;}
                LOG.error("Order message quarantined topic={} partition={} offset={} reason={}",record.topic(),record.partition(),record.offset(),failure.getClass().getSimpleName());
            }
            // The business transaction or durable quarantine has committed. A failed offset commit only causes safe redelivery.
            try {consumer.commitSync(Map.of(partition,new OffsetAndMetadata(record.offset()+1)),Duration.ofMillis(timeout));processed++;}
            catch(RuntimeException lostCommit){consumer.seek(partition,record.offset());blocked.add(partition);}
        }
        return processed;
    }
    void accept(String topic,String key,String value) {
        var e=store.decode(value);
        if(!e.orderId().toString().equals(key))throw new IllegalArgumentException("Order routing key mismatch");
        switch(topic) {
            case INVENTORY -> inventory.consume(e);
            case PAYMENT -> payments.consume(e);
            case REPLIES -> orders.consume(e);
            default -> throw new IllegalArgumentException("Unexpected topic");
        }
    }
    private boolean failed(ConsumerRecord<String,String> r,RuntimeException error) {
        return store.db.tx(()->{
            boolean invalid=error instanceof IllegalArgumentException;
            return store.db.jdbc().queryForObject("""
                INSERT INTO order_message_failures(topic,partition_id,offset_id,payload,last_error,quarantined)
                VALUES (?,?,?,?,?,?) ON CONFLICT(topic,partition_id,offset_id) DO UPDATE
                  SET attempts=order_message_failures.attempts+1,updated_at=clock_timestamp(),
                    quarantined=order_message_failures.quarantined OR EXCLUDED.quarantined OR order_message_failures.attempts>=4,
                    last_error=EXCLUDED.last_error RETURNING quarantined
                """,Boolean.class,r.topic(),r.partition(),r.offset(),r.value()==null?"<Kafka tombstone: null value>":r.value(),error.getClass().getSimpleName(),invalid);
        });
    }
    @PreDestroy public synchronized void close() {
        if(consumer!=null)consumer.close(Duration.ofSeconds(2));if(producer!=null)producer.close(Duration.ofSeconds(2));
    }
}

package com.example.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static com.example.booking.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties={"lab.maintenance-enabled=false","lab.outbox-controls-enabled=true"})
class OutboxIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired BookingStore store;
    @Autowired BookingService bookings;
    @Autowired PaymentProcessor payments;
    @Autowired LocalProvider provider;
    @Autowired LocalNotificationSink sink;
    @Autowired OutboxDispatcher dispatcher;
    Booking intent() {
        Event e=bookings.createEvent(new EventRequest("outbox-"+UUID.randomUUID(),2));
        Booking b=bookings.hold(new HoldRequest(e.id(),1,"buyer-"+UUID.randomUUID(),120),UUID.randomUUID().toString());
        return bookings.checkout(b.id(),"c-"+b.id(),new CheckoutRequest(Scenario.SUCCESS,0));
    }
    Booking confirmed() {Booking b=intent();return payments.reconcile(b.payment().id());}
    List<UUID> events(UUID booking) {return store.jdbc().queryForList("SELECT id FROM booking_outbox WHERE booking_id=? ORDER BY booking_version",UUID.class,booking);}
    int receipts(UUID event) {return store.jdbc().queryForObject("SELECT count(*) FROM local_notification_receipts WHERE event_id=?",Integer.class,event);}
    @Test void committedConfirmationHasUndeliveredWorkAndDuplicateCallbackDoesNotCreateAnotherEvent() {
        Booking b=confirmed();UUID event=events(b.id()).getFirst();
        assertThat(store.jdbc().queryForObject("SELECT delivered_at FROM booking_outbox WHERE id=?",java.sql.Timestamp.class,event)).isNull();
        assertThat(receipts(event)).isZero();
        payments.callback(b.payment().id(),new CallbackRequest("duplicate/"+b.id(),Outcome.SUCCESS));
        assertThat(events(b.id())).containsExactly(event);
        assertThat(dispatcher.dispatch(event,true)).isTrue();assertThat(receipts(event)).isEqualTo(1);
        assertThat(dispatcher.dispatch(event,true)).isFalse();
    }
    @Test void rolledBackConfirmationAndOutboxDisappearTogetherWhileProviderReceiptSurvives() {
        Booking b=intent();provider.accept(b.payment());
        assertThatThrownBy(()->store.tx(() -> {
            payments.callback(b.payment().id(),new CallbackRequest("rolled-back/"+b.id(),Outcome.SUCCESS));
            assertThat(events(b.id())).hasSize(1);throw new IllegalStateException("rollback inventory transaction");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(bookings.get(b.id()).state()).isEqualTo("CHECKOUT");assertThat(events(b.id())).isEmpty();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM provider_receipts WHERE payment_id=?",Integer.class,b.payment().id())).isEqualTo(1);
        assertThat(payments.reconcile(b.payment().id()).state()).isEqualTo("CONFIRMED");assertThat(events(b.id())).hasSize(1);
    }
    @Test void cancellationAndItsEventRollBackTogetherAndRepeatedCancelIsIdempotent() {
        Booking b=intent();
        assertThatThrownBy(()->store.tx(() -> {bookings.cancel(b.id());throw new IllegalStateException();})).isInstanceOf(IllegalStateException.class);
        assertThat(events(b.id())).isEmpty();assertThat(bookings.get(b.id()).state()).isEqualTo("CHECKOUT");
        bookings.cancel(b.id());bookings.cancel(b.id());assertThat(events(b.id())).hasSize(1);
        assertThat(store.jdbc().queryForObject("SELECT delivery_version FROM bookings WHERE id=?",Long.class,b.id())).isEqualTo(1);
    }
    @Test void responseLossAfterConsumerCommitReplaysInboxWithoutAnotherLocalEffect() {
        Booking b=confirmed();UUID event=events(b.id()).getFirst();
        LocalNotificationSink lost=new LocalNotificationSink(store) {
            @Override public Map<String,Object> consume(UUID id) {super.consume(id);throw new IllegalStateException("lost acknowledgement");}
        };
        assertThat(new OutboxDispatcher(store,lost,false).dispatch(event,true)).isTrue();
        assertThat(receipts(event)).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT delivered_at FROM booking_outbox WHERE id=?",java.sql.Timestamp.class,event)).isNull();
        assertThat(new OutboxDispatcher(store,new LocalNotificationSink(store),false).dispatch(event,true)).isTrue();
        assertThat(receipts(event)).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT deliveries FROM notification_inbox WHERE event_id=?",Integer.class,event)).isEqualTo(2);
        assertThat(store.jdbc().queryForObject("SELECT attempts FROM booking_outbox WHERE id=?",Integer.class,event)).isEqualTo(2);
    }
    @Test void cancellationBeforeConfirmationDeliveryCannotReactivateProjection() {
        Booking b=confirmed();bookings.cancel(b.id());var events=events(b.id());
        assertThat(sink.consume(events.get(1)).get("disposition")).isEqualTo("APPLIED");
        assertThat(sink.consume(events.getFirst()).get("disposition")).isEqualTo("STALE_IGNORED");
        assertThat(receipts(events.getFirst())).isZero();assertThat(receipts(events.get(1))).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT state FROM booking_delivery_projection WHERE booking_id=?",String.class,b.id())).isEqualTo("CANCELLED");
        assertThat(store.jdbc().queryForObject("SELECT booking_version FROM booking_delivery_projection WHERE booking_id=?",Long.class,b.id())).isEqualTo(2);
        for(UUID id:events) dispatcher.dispatch(id,true);
        assertThat(receipts(events.getFirst())).isZero();
    }
    @Test void inboxAndReceiptRollbackTogetherAndConcurrentDuplicatesApplyOnce() throws Exception {
        Booking b=confirmed();UUID event=events(b.id()).getFirst();
        assertThatThrownBy(()->store.tx(() -> {sink.consume(event);throw new IllegalStateException();})).isInstanceOf(IllegalStateException.class);
        assertThat(receipts(event)).isZero();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM notification_inbox WHERE event_id=?",Integer.class,event)).isZero();
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Future<Map<String,Object>>> calls=new ArrayList<>();for(int i=0;i<16;i++) calls.add(pool.submit(()->sink.consume(event)));
            long originals=0;for(var call:calls) if(Boolean.FALSE.equals(call.get(5,TimeUnit.SECONDS).get("replayed"))) originals++;
            assertThat(originals).isEqualTo(1);
        }
        assertThat(receipts(event)).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT deliveries FROM notification_inbox WHERE event_id=?",Integer.class,event)).isEqualTo(16);
    }
    @Test void activeLeasePreventsConcurrentDispatchAndStaleAckCannotFinishNewOwner() throws Exception {
        Booking b=confirmed();UUID event=events(b.id()).getFirst();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        LocalNotificationSink paused=new LocalNotificationSink(store) {
            @Override public Map<String,Object> consume(UUID id) {
                var result=super.consume(id);entered.countDown();
                try {if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("test timed out");}
                catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}return result;
            }
        };
        try(var pool=Executors.newSingleThreadExecutor()) {
            var run=pool.submit(()->new OutboxDispatcher(store,paused,false).dispatch(event,true));
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.dispatch(event,true)).isFalse();
            assertThat(bookings.hold(new HoldRequest(b.eventId(),2,"unrelated",120),UUID.randomUUID().toString()).state()).isEqualTo("HELD");
            UUID original=store.jdbc().queryForObject("SELECT lease_token FROM booking_outbox WHERE id=?",UUID.class,event);
            UUID replacement=UUID.randomUUID();store.jdbc().update("UPDATE booking_outbox SET lease_token=? WHERE id=?",replacement,event);
            assertThat(dispatcher.acknowledge(event,original)).isFalse();
            store.jdbc().update("UPDATE booking_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",event);
            assertThat(dispatcher.acknowledge(event,replacement)).isFalse();
            release.countDown();run.get(5,TimeUnit.SECONDS);
            assertThat(dispatcher.dispatch(event,true)).isTrue();assertThat(receipts(event)).isEqualTo(1);
        } finally {release.countDown();}
    }
    @Test void sharedDatabaseExceptionRetainsClaimWithoutFabricatedDelivery() {
        Booking b=confirmed();UUID event=events(b.id()).getFirst();
        LocalNotificationSink unavailable=mock(LocalNotificationSink.class);
        when(unavailable.consume(event)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("shared outage"));
        assertThatThrownBy(()->new OutboxDispatcher(store,unavailable,false).dispatch(event,true)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(receipts(event)).isZero();
        assertThat(store.jdbc().queryForObject("SELECT lease_until FROM booking_outbox WHERE id=?",java.sql.Timestamp.class,event)).isNotNull();
        store.jdbc().update("UPDATE booking_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",event);
        assertThat(dispatcher.dispatch(event,true)).isTrue();
    }
    @Test void lateSuccessHasNoConfirmationEventAndCannotOverrideCancellation() {
        Booking b=intent();bookings.cancel(b.id());payments.reconcile(b.payment().id());
        assertThat(events(b.id())).hasSize(1);
        assertThat(store.jdbc().queryForObject("SELECT kind FROM booking_outbox WHERE booking_id=?",String.class,b.id())).isEqualTo("CANCELLED");
        assertThat(bookings.get(b.id()).reconciliation()).isEqualTo("REFUND_REQUIRED");
    }
    @Test void sinkFailureDoesNotSkipLaterHealthyEvents() {
        Booking bad=confirmed(),good=confirmed();UUID first=events(bad.id()).getFirst(),second=events(good.id()).getFirst();
        store.jdbc().update("UPDATE booking_outbox SET next_at=clock_timestamp()-interval '1 minute' WHERE id=?",first);
        LocalNotificationSink broken=new LocalNotificationSink(store) {
            @Override public Map<String,Object> consume(UUID id) {if(id.equals(first))throw new IllegalArgumentException();return super.consume(id);}
        };
        new OutboxDispatcher(store,broken,false).dispatchBatch();
        assertThat(receipts(first)).isZero();assertThat(receipts(second)).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT last_error FROM booking_outbox WHERE id=?",String.class,first)).isEqualTo("IllegalArgumentException");
        dispatcher.dispatch(first,true);
    }
    @Test void snapshotHelperRequiresTransactionAndDrainStopsNewOutboxTicks() {
        Booking b=confirmed();
        assertThatThrownBy(()->store.enqueueSnapshot(b.id(),"CONFIRMED")).isInstanceOf(IllegalStateException.class);
        OutboxDispatcher calls=mock(OutboxDispatcher.class);AdmissionGate gate=mock(AdmissionGate.class);when(gate.enter()).thenReturn(false);
        new OutboxWorker(calls,gate,true,true).tick();verifyNoInteractions(calls);
        when(gate.enter()).thenReturn(true);new OutboxWorker(calls,gate,true,true).tick();verify(calls).dispatchBatch();verify(gate).leave();
    }
}

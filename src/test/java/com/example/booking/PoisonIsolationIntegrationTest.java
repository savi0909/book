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
@SpringBootTest(properties={"lab.maintenance-enabled=false","lab.poison-controls-enabled=true"})
class PoisonIsolationIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired BookingStore store;
    @Autowired BookingService bookings;
    @Autowired PaymentProcessor payments;
    @Autowired LocalProvider provider;
    @Autowired RecoveryIsolation isolation;
    Booking intent(Event e,int seat) {
        Booking b=bookings.hold(new HoldRequest(e.id(),seat,"p-"+UUID.randomUUID(),120),UUID.randomUUID().toString());
        return bookings.checkout(b.id(),"c-"+b.id(),new CheckoutRequest(Scenario.SUCCESS,0));
    }
    void due(UUID id) {store.jdbc().update("UPDATE payments SET next_at=clock_timestamp()-interval '1 second' WHERE id=?",id);}
    Booking poison() {
        Booking b=intent(bookings.createEvent(new EventRequest("poison-"+UUID.randomUUID(),1)),1);
        isolation.fixture(b.payment().id(),true);return b;
    }
    void quarantine(UUID id) {
        for(int i=0;i<3;i++) {due(id);payments.recover(id,false);}
        assertThat(payments.recoveryStatus(id).get("quarantinedAt")).isNotNull();
    }
    @Test void firstPoisonCandidateDoesNotSkipHealthyItemsAndAttemptsStopAtThree() {
        Event e=bookings.createEvent(new EventRequest("batch",5));Booking bad=intent(e,1);
        isolation.fixture(bad.payment().id(),true);
        List<Booking> healthy=new ArrayList<>();for(int i=2;i<=5;i++) healthy.add(intent(e,i));
        store.jdbc().update("UPDATE payments SET next_at=clock_timestamp()-interval '1 minute' WHERE id=?",bad.payment().id());
        payments.recoverBatch();
        for(Booking b:healthy) assertThat(bookings.get(b.id()).state()).isEqualTo("CONFIRMED");
        assertThat(payments.recoveryStatus(bad.payment().id()).get("itemFailures")).isEqualTo(1);
        for(int i=0;i<2;i++) {due(bad.payment().id());payments.recoverBatch();}
        for(int i=0;i<4;i++) {due(bad.payment().id());payments.recoverBatch();}
        var state=payments.recoveryStatus(bad.payment().id());
        assertThat(state.get("attempts")).isEqualTo(3);assertThat(state.get("state")).isEqualTo("UNKNOWN");
        assertThat(state.get("quarantineReason")).isEqualTo("PoisonFixtureException");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM provider_receipts WHERE payment_id=?",Integer.class,bad.payment().id())).isEqualTo(1);
        assertThat(isolation.history(bad.payment().id()).stream().filter(h->h.get("action").equals("QUARANTINED")).count()).isEqualTo(1);
        assertThatThrownBy(()->payments.reconcile(bad.payment().id())).isInstanceOf(ApiException.class).hasMessageContaining("redrive");
        isolation.fixture(bad.payment().id(),false);payments.redrive(bad.payment().id(),"fixed");
        assertThat(bookings.get(bad.id()).state()).isEqualTo("CONFIRMED");
    }
    @Test void independentWorkersSerializeDuplicateAndDifferentKeyRedrives() throws Exception {
        Booking b=poison();UUID id=b.payment().id();quarantine(id);isolation.fixture(id,false);
        var other=new PaymentProcessor(store,provider,new RecoveryIsolation(store,true));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->payments.redrive(id,"same-key"));var c=pool.submit(()->other.redrive(id,"same-key"));
            var results=List.of(a.get(5,TimeUnit.SECONDS),c.get(5,TimeUnit.SECONDS));
            assertThat(results.stream().filter(x->Boolean.TRUE.equals(x.get("replayed"))).count()).isEqualTo(1);
        }
        assertThat(other.recoveryStatus(id).get("attempts")).isEqualTo(4);
        assertThat(other.recoveryStatus(id).get("redriveCount")).isEqualTo(1);
        assertThat(bookings.get(b.id()).payment().id()).isEqualTo(id);
        assertThat(bookings.audit(b.id()).stream().filter(h->h.get("action").equals("CONFIRMED")).count()).isEqualTo(1);
        assertThatThrownBy(()->other.redrive(id,"different-key")).isInstanceOf(ApiException.class);
    }
    @Test void brokenRedrivesStayQuarantinedAndCannotResetBudgets() {
        Booking b=poison();UUID id=b.payment().id();quarantine(id);
        store.jdbc().update("UPDATE payments SET retry_exhausted=true WHERE id=?",id);
        payments.redrive(id,"r1");assertThat(payments.redrive(id,"r1").get("replayed")).isEqualTo(true);
        payments.redrive(id,"r2");
        assertThatThrownBy(()->payments.redrive(id,"r3")).isInstanceOf(ApiException.class).hasMessageContaining("Two");
        var state=payments.recoveryStatus(id);
        assertThat(state.get("attempts")).isEqualTo(5);assertThat(state.get("redriveCount")).isEqualTo(2);
        assertThat(state.get("retryExhausted")).isEqualTo(true);assertThat(state.get("quarantinedAt")).isNotNull();
        // A verified immutable callback remains a resolution path when automatic/redrive budgets end.
        payments.callback(id,new CallbackRequest("verified/"+id,Outcome.SUCCESS));
        assertThat(bookings.get(b.id()).state()).isEqualTo("CONFIRMED");
    }
    @Test void staleFailureAndActiveLeaseCannotChangeAnotherOwnersWork() {
        Booking b=poison();UUID id=b.payment().id();quarantine(id);
        UUID owner=UUID.randomUUID();store.jdbc().update("UPDATE payments SET lease_token=?,lease_until=clock_timestamp()+interval '5 seconds' WHERE id=?",owner,id);
        assertThatThrownBy(()->payments.redrive(id,"blocked")).isInstanceOf(ApiException.class);
        assertThat(payments.recoveryStatus(id).get("redriveCount")).isEqualTo(0);
        int history=isolation.history(id).size();isolation.failed(id,UUID.randomUUID(),new IllegalStateException());
        assertThat(isolation.history(id)).hasSize(history);
        store.jdbc().update("UPDATE payments SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",id);
        isolation.failed(id,owner,new IllegalStateException());assertThat(isolation.history(id)).hasSize(history);
        store.jdbc().update("UPDATE payments SET lease_token=NULL,lease_until=NULL WHERE id=?",id);
        isolation.fixture(id,false);payments.redrive(id,"blocked");assertThat(bookings.get(b.id()).state()).isEqualTo("CONFIRMED");
    }
    @Test void lateRedrivePreservesReplacementAndRequiresRefund() {
        Booking old=poison();UUID id=old.payment().id();quarantine(id);
        store.jdbc().update("UPDATE bookings SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",old.id());
        Booking replacement=bookings.hold(new HoldRequest(old.eventId(),1,"replacement",120),UUID.randomUUID().toString());
        isolation.fixture(id,false);payments.redrive(id,"late");
        assertThat(bookings.get(old.id()).state()).isEqualTo("EXPIRED");
        assertThat(bookings.get(old.id()).reconciliation()).isEqualTo("REFUND_REQUIRED");
        assertThat(bookings.get(replacement.id()).state()).isEqualTo("HELD");
    }
    @Test void sharedDatabaseFailureNeverQuarantinesPaymentAndLeaseIsRecoverable() {
        Booking b=intent(bookings.createEvent(new EventRequest("db-failure",1)),1);
        LocalProvider failing=mock(LocalProvider.class);
        when(failing.accept(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("shared dependency"));
        var worker=new PaymentProcessor(store,failing,isolation);
        assertThatThrownBy(()->worker.recover(b.payment().id(),false)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var state=payments.recoveryStatus(b.payment().id());
        assertThat(state.get("itemFailures")).isEqualTo(0);assertThat(state.get("quarantinedAt")).isNull();
        assertThat(state.get("leaseUntil")).isNotNull();
        store.jdbc().update("UPDATE payments SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",b.payment().id());
        assertThat(payments.reconcile(b.payment().id()).state()).isEqualTo("CONFIRMED");
    }
    @Test void expiryFailureDoesNotSkipPaymentPhase() {
        BookingService broken=mock(BookingService.class);when(broken.expireBatch()).thenThrow(new IllegalStateException());
        PaymentProcessor recovery=mock(PaymentProcessor.class);
        new Maintenance(broken,recovery,true,new AdmissionGate(mock(org.springframework.context.ConfigurableApplicationContext.class))).tick();
        verify(recovery).recoverBatch();
    }
    @Test void providerUnavailableAndMalformedReplyAreDependencyFailures() {
        Booking b=intent(bookings.createEvent(new EventRequest("provider-classification",1)),1);
        ProviderBoundary invalid=mock(ProviderBoundary.class);when(invalid.enabled()).thenReturn(true);
        when(invalid.call(anyString(),anyString())).thenReturn("SUCCESS,invalid-time");
        var worker=new PaymentProcessor(store,new LocalProvider(store,invalid),isolation);
        worker.recover(b.payment().id(),false);
        var state=payments.recoveryStatus(b.payment().id());
        assertThat(state.get("lastError")).isEqualTo("INVALID_RECEIPT");
        assertThat(state.get("itemFailures")).isEqualTo(0);assertThat(state.get("quarantinedAt")).isNull();
        assertThat(payments.reconcile(b.payment().id()).state()).isEqualTo("CONFIRMED");
    }
    @Test void quarantinedUncertaintyStillClosesRemoteCheckoutByAgeAndAllowsReplay() {
        Event e=bookings.createEvent(new EventRequest("quarantine-admission",2));Booking b=intent(e,1);
        isolation.fixture(b.payment().id(),true);quarantine(b.payment().id());
        ProviderBoundary remote=mock(ProviderBoundary.class);when(remote.enabled()).thenReturn(true);
        var remoteBookings=new BookingService(store,remote);
        store.jdbc().update("UPDATE payments SET created_at=clock_timestamp()-interval '31 seconds' WHERE id=?",b.payment().id());
        Booking fresh=bookings.hold(new HoldRequest(e.id(),2,"fresh-"+UUID.randomUUID(),120),UUID.randomUUID().toString());
        assertThat(((Number)remoteBookings.providerBacklog().get("quarantined")).intValue()).isPositive();
        assertThatThrownBy(()->remoteBookings.checkout(fresh.id(),"new",new CheckoutRequest(Scenario.SUCCESS,0))).isInstanceOf(ApiException.class).hasMessageContaining("paused");
        assertThat(remoteBookings.checkout(b.id(),"c-"+b.id(),new CheckoutRequest(Scenario.SUCCESS,0)).payment().id()).isEqualTo(b.payment().id());
        isolation.fixture(b.payment().id(),false);payments.redrive(b.payment().id(),"resolve-aged");
    }
    @Test void responseLossAfterRedriveClaimConsumesKeyAndCannotDispatchItAgain() {
        Booking b=poison();UUID id=b.payment().id();quarantine(id);isolation.fixture(id,false);
        LocalProvider unavailable=mock(LocalProvider.class);
        when(unavailable.accept(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("after claimed commit"));
        var crashed=new PaymentProcessor(store,unavailable,isolation);
        assertThatThrownBy(()->crashed.redrive(id,"lost-response")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(payments.redrive(id,"lost-response").get("replayed")).isEqualTo(true);
        assertThat(payments.recoveryStatus(id).get("attempts")).isEqualTo(4);
        assertThat(payments.recoveryStatus(id).get("redriveCount")).isEqualTo(1);
        assertThatThrownBy(()->payments.redrive(id,"next-deliberate-key")).isInstanceOf(ApiException.class);
        store.jdbc().update("UPDATE payments SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",id);
        assertThat(payments.redrive(id,"lost-response").get("replayed")).isEqualTo(true);
        payments.redrive(id,"next-deliberate-key");assertThat(bookings.get(b.id()).state()).isEqualTo("CONFIRMED");
    }
    @Test void disabledFaultsIgnoreRetainedFixtureFlag() {
        Booking b=poison();UUID id=b.payment().id();
        new PaymentProcessor(store,provider,new RecoveryIsolation(store,false)).recover(id,false);
        assertThat(bookings.get(b.id()).state()).isEqualTo("CONFIRMED");
        assertThat(payments.recoveryStatus(id).get("itemFailures")).isEqualTo(0);
    }
    @Test void missingFixtureAndMalformedRedriveKeyDoNotAdmitWork() {
        assertThatThrownBy(()->isolation.fixture(UUID.randomUUID(),true)).isInstanceOf(ApiException.class).hasMessage("Resource not found");
        Booking b=poison();UUID id=b.payment().id();quarantine(id);
        assertThatThrownBy(()->payments.redrive(id,"invalid key")).isInstanceOf(ApiException.class);
        assertThat(payments.recoveryStatus(id).get("redriveCount")).isEqualTo(0);
        isolation.fixture(id,false);payments.redrive(id,"valid-key");
    }
}

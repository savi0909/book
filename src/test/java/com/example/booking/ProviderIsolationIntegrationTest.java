package com.example.booking;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.example.booking.Models.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties="lab.maintenance-enabled=false")
class ProviderIsolationIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    static final Map<String,String> receipts=new ConcurrentHashMap<>();
    static final AtomicInteger active=new AtomicInteger(),max=new AtomicInteger(),calls=new AtomicInteger();
    static volatile boolean slow,unavailable;
    static final ExecutorService workers=Executors.newFixedThreadPool(4);
    static final HttpServer remote;
    static {
        try {
            remote=HttpServer.create(new InetSocketAddress(0),16);remote.setExecutor(workers);
            remote.createContext("/payments/",e->{
                int n=active.incrementAndGet();max.accumulateAndGet(n,Math::max);calls.incrementAndGet();
                try {
                    String id=e.getRequestURI().getPath();
                    String input=new String(e.getRequestBody().readAllBytes());
                    if(!unavailable) receipts.putIfAbsent(id,input);
                    if(slow) Thread.sleep(900);
                    byte[] result=(unavailable?"unavailable":receipts.get(id)).getBytes();
                    e.sendResponseHeaders(unavailable?503:200,result.length);e.getResponseBody().write(result);
                } catch(InterruptedException ex){Thread.currentThread().interrupt();}
                catch(java.io.IOException ignored) { /* A timed-out caller can close the connection. */ }
                finally {active.decrementAndGet();e.close();}
            });remote.start();
        } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
        r.add("lab.provider-url",()->"http://localhost:"+remote.getAddress().getPort());
    }
    @Autowired BookingService bookings;
    @Autowired PaymentProcessor payments;
    @Autowired BookingStore store;
    @Autowired ProviderBoundary boundary;
    @AfterAll static void stop(){remote.stop(0);workers.shutdownNow();}
    Booking intent(int seat,Event e) {
        Booking b=bookings.hold(new HoldRequest(e.id(),seat,"p-"+UUID.randomUUID(),120),UUID.randomUUID().toString());
        return bookings.checkout(b.id(),"c-"+b.id(),new CheckoutRequest(Scenario.SUCCESS,0));
    }
    @Test void slowAmbiguousAcceptanceKeepsDbFreeAndRecoversWithStableIdentity() throws Exception {
        slow=true;unavailable=false;
        Event e=bookings.createEvent(new EventRequest("provider",4));Booking a=intent(1,e),b=intent(2,e);
        try(var tasks=Executors.newFixedThreadPool(2)) {
            var first=tasks.submit(()->payments.reconcile(a.payment().id()));var second=tasks.submit(()->payments.reconcile(b.payment().id()));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(active.get()<2 && System.nanoTime()<end) Thread.sleep(10);
            assertThat(active.get()).isEqualTo(2);
            assertThatThrownBy(()->boundary.call("/payments/"+UUID.randomUUID(),"SUCCESS,0")).isInstanceOf(ProviderBoundary.Unavailable.class).hasMessage("BULKHEAD_FULL");
            assertThat(bookings.hold(new HoldRequest(e.id(),3,"healthy",120),"healthy").state()).isEqualTo("HELD");
            assertThat(bookings.seats(e.id(),4,0)).hasSize(4);
            assertThat(first.get(3,TimeUnit.SECONDS).payment().state()).isEqualTo("UNKNOWN");
            assertThat(second.get(3,TimeUnit.SECONDS).payment().state()).isEqualTo("UNKNOWN");
            assertThat(active.get()).isPositive(); // Remote work survived the caller deadline.
        } finally {slow=false;}
        Thread.sleep(1000);
        assertThat(payments.reconcile(a.payment().id()).state()).isEqualTo("CONFIRMED");
        assertThat(payments.reconcile(b.payment().id()).state()).isEqualTo("CONFIRMED");
        assertThat(receipts.keySet()).contains("/payments/"+a.payment().id(),"/payments/"+b.payment().id());
        assertThat(max.get()).isEqualTo(2);
    }
    @Test void automaticBudgetIsDurableAndReplayBypassesBacklogAdmission() {
        Event e=bookings.createEvent(new EventRequest("budget",2));Booking b=intent(1,e);
        store.jdbc().update("UPDATE payments SET attempts=4,retry_started_at=clock_timestamp() WHERE id=?",b.payment().id());
        Booking aged=intent(1,bookings.createEvent(new EventRequest("elapsed-budget",1)));
        store.jdbc().update("UPDATE payments SET attempts=1,retry_started_at=clock_timestamp()-interval '11 seconds' WHERE id=?",aged.payment().id());
        int before=calls.get();payments.recoverBatch();
        assertThat(calls.get()).isEqualTo(before);
        assertThat(store.jdbc().queryForObject("SELECT retry_exhausted FROM payments WHERE id=?",Boolean.class,b.payment().id())).isTrue();
        assertThat(store.jdbc().queryForObject("SELECT retry_exhausted FROM payments WHERE id=?",Boolean.class,aged.payment().id())).isTrue();
        assertThat(payments.reconcile(aged.payment().id()).state()).isEqualTo("CONFIRMED");
        store.jdbc().update("UPDATE payments SET created_at=clock_timestamp()-interval '31 seconds' WHERE id=?",b.payment().id());
        Booking fresh=bookings.hold(new HoldRequest(e.id(),2,"fresh",120),"fresh");
        assertThatThrownBy(()->bookings.checkout(fresh.id(),"new",new CheckoutRequest(Scenario.SUCCESS,0))).isInstanceOf(ApiException.class).hasMessageContaining("paused");
        assertThat(bookings.checkout(b.id(),"c-"+b.id(),new CheckoutRequest(Scenario.SUCCESS,0)).payment().id()).isEqualTo(b.payment().id());
        assertThat(payments.reconcile(b.payment().id()).state()).isEqualTo("CONFIRMED");
    }
    @Test void breakerRejectsOpenCallsAndAdmitsOneRecoveryProbe() throws Exception {
        var isolated=new ProviderBoundary("http://localhost:"+remote.getAddress().getPort());unavailable=true;
        for(int i=0;i<3;i++) assertThatThrownBy(()->isolated.call("/payments/"+UUID.randomUUID(),"SUCCESS,0")).isInstanceOf(ProviderBoundary.Unavailable.class);
        assertThat(isolated.status().get("state")).isEqualTo("OPEN");
        int before=calls.get();assertThatThrownBy(()->isolated.call("/payments/"+UUID.randomUUID(),"SUCCESS,0")).hasMessage("CIRCUIT_OPEN");assertThat(calls.get()).isEqualTo(before);
        Thread.sleep(2100);unavailable=false;slow=true;
        try(var task=Executors.newSingleThreadExecutor()) {
            var probe=task.submit(()->{try{isolated.call("/payments/"+UUID.randomUUID(),"SUCCESS,0");}catch(ProviderBoundary.Unavailable ignored){}});
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(!Boolean.TRUE.equals(isolated.status().get("probeInFlight")) && System.nanoTime()<end) Thread.sleep(5);
            assertThat(isolated.status().get("probeInFlight")).isEqualTo(true);
            assertThatThrownBy(()->isolated.call("/payments/"+UUID.randomUUID(),"SUCCESS,0")).hasMessage("BULKHEAD_FULL");
            probe.get(3,TimeUnit.SECONDS);assertThat(isolated.status().get("state")).isEqualTo("OPEN");
        } finally {slow=false;unavailable=false;}
        Thread.sleep(2100);isolated.call("/payments/"+UUID.randomUUID(),"SUCCESS,0");assertThat(isolated.status().get("state")).isEqualTo("CLOSED");
    }
    @Test void staleSuccessCannotCloseAnOpenGeneration() {
        var isolated=new ProviderBoundary("http://unused");
        long slowCall=isolated.enter(),first=isolated.enter();isolated.finish(first,false);
        long second=isolated.enter();isolated.finish(second,false);
        long third=isolated.enter();isolated.finish(third,false);
        assertThat(isolated.status().get("state")).isEqualTo("OPEN");
        isolated.finish(slowCall,true);
        assertThat(isolated.status().get("state")).isEqualTo("OPEN");
        assertThat(isolated.status().get("inFlight")).isEqualTo(0);
    }
}

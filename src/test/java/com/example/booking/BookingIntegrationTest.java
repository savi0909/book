package com.example.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import static com.example.booking.Models.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="lab.maintenance-enabled=false")
class BookingIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired BookingService bookings;
    @Autowired PaymentProcessor payments;
    @Autowired LocalProvider provider;
    @Autowired BookingStore store;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newHttpClient();

    Event event() {return bookings.createEvent(new EventRequest("test-"+UUID.randomUUID(),4));}
    HoldRequest request(UUID event,int seat,String buyer) {return new HoldRequest(event,seat,buyer,120);}
    Booking hold(Event event,int seat) {return bookings.hold(request(event.id(),seat,"buyer-"+UUID.randomUUID()),"hold-"+UUID.randomUUID());}
    Booking checkout(Booking b,Scenario scenario) {return bookings.checkout(b.id(),"checkout-"+b.id(),new CheckoutRequest(scenario,0));}
    void expire(UUID booking) {store.jdbc().update("UPDATE bookings SET expires_at=clock_timestamp()-interval '1 millisecond' WHERE id=?",booking);}
    <T> List<T> race(int count,IntFunction<T> work) throws Exception {
        try(var executor=Executors.newFixedThreadPool(count)) {
            var start=new CountDownLatch(1);
            List<Future<T>> futures=new ArrayList<>();
            for(int i=0;i<count;i++){int index=i;futures.add(executor.submit(()->{start.await();return work.apply(index);}));}
            start.countDown();
            List<T> results=new ArrayList<>();
            for(var f:futures) results.add(f.get(15,TimeUnit.SECONDS));
            return results;
        }
    }
    @Test void failoverControlsAreDisabledByDefaultAndDelayCannotMutateCheckout() throws Exception {
        assertThat(call("POST","/api/demo/failover/drain",null,null).statusCode()).isEqualTo(404);
        Booking booking=hold(event(),1);
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/bookings/"+booking.id()+"/checkout"))
            .header("Content-Type","application/json").header("Idempotency-Key","disabled")
            .header("X-Lab-Response-Delay-Ms","100")
            .POST(HttpRequest.BodyPublishers.ofString("{\"scenario\":\"SUCCESS\",\"delayMs\":0}")).build();
        assertThat(http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        assertThat(bookings.get(booking.id()).payment()).isNull();
    }
    @Test void manyBuyersCannotOversellOneSeat() throws Exception {
        Event e=event();
        var results=race(24,i->{try{return bookings.hold(request(e.id(),1,"racer-"+i),"key-"+UUID.randomUUID()).state();}
            catch(ApiException ex){assertThat(ex.code()).isEqualTo("SEAT_UNAVAILABLE");return "CONFLICT";}});
        assertThat(results).filteredOn("HELD"::equals).hasSize(1);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM bookings WHERE event_id=? AND state='HELD'",Integer.class,e.id())).isEqualTo(1);
    }
    @Test void concurrentDuplicateHoldReturnsOneDurableBooking() throws Exception {
        Event e=event();String key=UUID.randomUUID().toString();HoldRequest r=request(e.id(),1,"same-buyer");
        var results=race(16,i->bookings.hold(r,key));
        assertThat(results.stream().map(Booking::id).distinct()).hasSize(1);
        assertThat(results).filteredOn(b->!b.replayed()).hasSize(1);
    }
    @Test void holdKeyCannotChangePayloadButIsScopedToBuyer() {
        Event e=event();String key=UUID.randomUUID().toString();
        bookings.hold(request(e.id(),1,"alice"),key);
        assertThatThrownBy(()->bookings.hold(request(e.id(),2,"alice"),key)).isInstanceOf(ApiException.class).hasMessageContaining("different hold input");
        assertThat(bookings.hold(request(e.id(),2,"bob"),key).state()).isEqualTo("HELD");
    }
    @Test void expiredReplayNeverCreatesAnotherHold() {
        Event e=event();String key=UUID.randomUUID().toString();HoldRequest r=request(e.id(),1,"replay-buyer");
        Booking b=bookings.hold(r,key);expire(b.id());
        Booking replay=bookings.hold(r,key);
        assertThat(replay.id()).isEqualTo(b.id());assertThat(replay.state()).isEqualTo("EXPIRED");assertThat(replay.replayed()).isTrue();
        assertThat(hold(e,1).id()).isNotEqualTo(b.id());
    }
    @Test void concurrentCheckoutHasOneIntentAndChangedInputConflicts() throws Exception {
        Booking b=hold(event(),1);String key=UUID.randomUUID().toString();
        var results=race(12,i->bookings.checkout(b.id(),key,new CheckoutRequest(Scenario.SUCCESS,0)));
        assertThat(results.stream().map(x->x.payment().id()).distinct()).hasSize(1);
        assertThat(results).filteredOn(x->!x.replayed()).hasSize(1);
        assertThatThrownBy(()->bookings.checkout(b.id(),key,new CheckoutRequest(Scenario.FAILURE,0))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->bookings.checkout(b.id(),"another-key",new CheckoutRequest(Scenario.SUCCESS,0))).isInstanceOf(ApiException.class);
    }
    @Test void successfulPaymentConfirmsAndKeepsSeatBooked() {
        Event e=event();Booking started=checkout(hold(e,1),Scenario.SUCCESS);
        Booking done=payments.reconcile(started.payment().id());
        assertThat(done.state()).isEqualTo("CONFIRMED");assertThat(done.payment().state()).isEqualTo("SUCCESS");
        assertThat(bookings.seats(e.id(),100,0).getFirst().availability()).isEqualTo("BOOKED");
        assertThatThrownBy(()->hold(e,1)).isInstanceOf(ApiException.class);
        assertThat(bookings.audit(done.id())).extracting(m->m.get("action")).contains("HELD","CHECKOUT_STARTED","CONFIRMED","PAYMENT_SUCCESS");
    }
    @Test void failedPaymentReleasesSeat() {
        Event e=event();Booking started=checkout(hold(e,1),Scenario.FAILURE);
        assertThat(payments.reconcile(started.payment().id()).state()).isEqualTo("PAYMENT_FAILED");
        assertThat(hold(e,1).state()).isEqualTo("HELD");
    }
    @Test void unknownOutcomeRecoversFromDurableReceipt() {
        Booking started=checkout(hold(event(),1),Scenario.UNKNOWN);
        Booking uncertain=payments.reconcile(started.payment().id());
        assertThat(uncertain.payment().state()).isEqualTo("UNKNOWN");assertThat(uncertain.state()).isEqualTo("CHECKOUT");
        assertThat(provider.lookup(started.payment().id()).outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(payments.reconcile(started.payment().id()).state()).isEqualTo("CONFIRMED");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM provider_receipts WHERE payment_id=?",Integer.class,started.payment().id())).isEqualTo(1);
    }
    @Test void lateSuccessCannotStealReassignedSeatAndNeedsRefund() {
        Event e=event();Booking old=checkout(hold(e,1),Scenario.UNKNOWN);
        payments.reconcile(old.payment().id());expire(old.id());Booking replacement=hold(e,1);
        Booking late=payments.callback(old.payment().id(),new CallbackRequest("late-"+UUID.randomUUID(),Outcome.SUCCESS));
        assertThat(late.state()).isEqualTo("EXPIRED");assertThat(late.reconciliation()).isEqualTo("REFUND_REQUIRED");
        assertThat(bookings.get(replacement.id()).state()).isEqualTo("HELD");
        assertThat(bookings.refund(old.payment().id()).reconciliation()).isEqualTo("REFUNDED_SIMULATED");
        assertThat(bookings.refund(old.payment().id()).replayed()).isTrue();
        assertThat(payments.callback(old.payment().id(),new CallbackRequest("late-again-"+UUID.randomUUID(),Outcome.SUCCESS)).reconciliation()).isEqualTo("REFUNDED_SIMULATED");
    }
    @Test void cancellationBeforeCallbackProducesRefundWithoutBookingSeat() {
        Event e=event();Booking b=checkout(hold(e,1),Scenario.UNKNOWN);payments.reconcile(b.payment().id());
        bookings.cancel(b.id());Booking replacement=hold(e,1);
        Booking result=payments.reconcile(b.payment().id());
        assertThat(result.state()).isEqualTo("CANCELLED");assertThat(result.reconciliation()).isEqualTo("REFUND_REQUIRED");
        assertThat(bookings.get(replacement.id()).state()).isEqualTo("HELD");
    }
    @Test void confirmedCancellationReleasesSeatAndFlagsRefund() {
        Event e=event();Booking b=checkout(hold(e,1),Scenario.SUCCESS);payments.reconcile(b.payment().id());
        Booking cancelled=bookings.cancel(b.id());
        assertThat(cancelled.state()).isEqualTo("CANCELLED");assertThat(cancelled.reconciliation()).isEqualTo("REFUND_REQUIRED");
        assertThat(hold(e,1).state()).isEqualTo("HELD");
    }
    @Test void duplicateCallbacksApplyStateAndAuditOnce() throws Exception {
        Booking b=checkout(hold(event(),1),Scenario.UNKNOWN);payments.reconcile(b.payment().id());String eventId="duplicate-"+UUID.randomUUID();
        var results=race(12,i->payments.callback(b.payment().id(),new CallbackRequest(eventId,Outcome.SUCCESS)));
        assertThat(results).allMatch(x->x.state().equals("CONFIRMED"));
        assertThat(results).filteredOn(x->!x.replayed()).hasSize(1);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM booking_audit WHERE booking_id=? AND action='CONFIRMED'",Integer.class,b.id())).isEqualTo(1);
    }
    @Test void callbackCannotChangeOutcomeOrReuseEventForAnotherPayment() {
        Event e=event();Booking a=checkout(hold(e,1),Scenario.UNKNOWN),b=checkout(hold(e,2),Scenario.UNKNOWN);
        payments.reconcile(a.payment().id());payments.reconcile(b.payment().id());String eventId="shared-"+UUID.randomUUID();
        payments.callback(a.payment().id(),new CallbackRequest(eventId,Outcome.SUCCESS));
        assertThatThrownBy(()->payments.callback(a.payment().id(),new CallbackRequest("conflict-"+UUID.randomUUID(),Outcome.FAILURE))).isInstanceOf(ApiException.class).hasMessageContaining("disagrees");
        assertThatThrownBy(()->payments.callback(b.payment().id(),new CallbackRequest(eventId,Outcome.SUCCESS))).isInstanceOf(ApiException.class).hasMessageContaining("Event ID");
        assertThat(bookings.get(b.id()).state()).isEqualTo("CHECKOUT");
    }
    @Test void simulatorDelayDoesNotKeepSeatTransactionLocked() {
        Booking b=hold(event(),1);Booking started=bookings.checkout(b.id(),"delay",new CheckoutRequest(Scenario.SUCCESS,10000));
        payments.reconcile(started.payment().id());
        long begin=System.nanoTime();assertThat(bookings.cancel(b.id()).state()).isEqualTo("CANCELLED");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin)).isLessThan(1500);
        assertThatThrownBy(()->payments.callback(started.payment().id(),new CallbackRequest("too-early",Outcome.SUCCESS))).isInstanceOf(ApiException.class).hasMessageContaining("not yet");
    }
    @Test void expiredCheckoutCannotStartAndSeatReadsAreLogicallyAvailable() {
        Event e=event();Booking b=hold(e,1);expire(b.id());
        assertThat(bookings.seats(e.id(),100,0).getFirst().availability()).isEqualTo("AVAILABLE");
        assertThatThrownBy(()->checkout(b,Scenario.SUCCESS)).isInstanceOf(ApiException.class).hasMessageContaining("valid HELD");
        assertThat(bookings.get(b.id()).state()).isEqualTo("EXPIRED");
    }
    @Test void expiryAfterWaitingForSeatLockPreventsConfirmation() throws Exception {
        Booking b=checkout(hold(event(),1),Scenario.UNKNOWN);payments.reconcile(b.payment().id());
        store.jdbc().update("UPDATE bookings SET expires_at=clock_timestamp()+interval '500 milliseconds' WHERE id=?",b.id());
        var locked=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var blocker=executor.submit(()->store.tx(()->{store.locked(b.id());locked.countDown();try{Thread.sleep(800);}catch(InterruptedException ex){throw new RuntimeException(ex);}return null;}));
            assertThat(locked.await(2,TimeUnit.SECONDS)).isTrue();
            Booking result=payments.callback(b.payment().id(),new CallbackRequest("wait-"+UUID.randomUUID(),Outcome.SUCCESS));
            blocker.get(3,TimeUnit.SECONDS);
            assertThat(result.state()).isEqualTo("EXPIRED");assertThat(result.reconciliation()).isEqualTo("REFUND_REQUIRED");
        }
    }
    @Test void abandonedLeaseRecoversAndStaleWorkerCannotApply() {
        Booking b=checkout(hold(event(),1),Scenario.SUCCESS);provider.accept(b.payment());UUID stale=UUID.randomUUID();
        store.jdbc().update("UPDATE payments SET lease_token=?,lease_until=clock_timestamp()-interval '1 second' WHERE id=?",stale,b.payment().id());
        Booking staleResult=payments.apply(b.payment().id(),"stale-"+UUID.randomUUID(),Outcome.SUCCESS,stale);
        assertThat(staleResult.state()).isEqualTo("CHECKOUT");
        assertThat(payments.reconcile(b.payment().id()).state()).isEqualTo("CONFIRMED");
    }
    @Test void databaseConstraintRejectsSecondActiveBookingEvenWithoutServiceLock() {
        Event e=event();hold(e,1);
        assertThatThrownBy(()->store.tx(()->{store.jdbc().update("""
            INSERT INTO bookings(id,event_id,seat_number,buyer_id,hold_key,ttl_seconds,state,expires_at)
            VALUES (?,?,1,'bypass',?,120,'CONFIRMED',clock_timestamp()+interval '2 minutes')
            """,UUID.randomUUID(),e.id(),UUID.randomUUID().toString());return null;})).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    HttpResponse<String> call(String method,String path,String body,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");
        if(key!=null) builder.header("Idempotency-Key",key);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void httpHoldCheckoutAndReplayContracts() throws Exception {
        Event e=event();String body=json.writeValueAsString(request(e.id(),1,"http-buyer")),key=UUID.randomUUID().toString();
        var first=call("POST","/api/holds",body,key);assertThat(first.statusCode()).isEqualTo(201);JsonNode data=json.readTree(first.body());
        assertThat(call("POST","/api/holds",body,key).statusCode()).isEqualTo(200);
        String path="/api/bookings/"+data.get("id").asText()+"/checkout";
        var checkout=call("POST",path,"{\"scenario\":\"SUCCESS\",\"delayMs\":0}","checkout");assertThat(checkout.statusCode()).isEqualTo(202);
        assertThat(call("POST",path,"{\"scenario\":\"SUCCESS\",\"delayMs\":0}","checkout").statusCode()).isEqualTo(200);
        UUID payment=UUID.fromString(json.readTree(checkout.body()).path("payment").path("id").asText());
        assertThat(json.readTree(call("POST","/api/demo/payments/"+payment+"/reconcile",null,null).body()).path("state").asText()).isEqualTo("CONFIRMED");
    }
    @Test void httpValidationRejectsMissingKeyFractionalAndOutOfBounds() throws Exception {
        Event e=event();String body=json.writeValueAsString(request(e.id(),1,"http-invalid"));
        assertThat(call("POST","/api/holds",body,null).statusCode()).isEqualTo(400);
        assertThat(call("POST","/api/holds",body,"bad key").statusCode()).isEqualTo(400);
        assertThat(call("POST","/api/demo/events","{\"name\":\"fraction\",\"seatCount\":2.5}",null).statusCode()).isEqualTo(400);
        assertThat(call("POST","/api/demo/events","{\"name\":\"too many\",\"seatCount\":201}",null).statusCode()).isEqualTo(400);
        assertThat(call("GET","/api/events?limit=0",null,null).statusCode()).isEqualTo(400);
        assertThat(call("GET","/api/events/not-a-uuid",null,null).statusCode()).isEqualTo(400);
    }
    @Test void httpNotFoundAndConflictUseExplicitEnvelope() throws Exception {
        Event e=event();hold(e,1);
        var conflict=call("POST","/api/holds",json.writeValueAsString(request(e.id(),1,"http-contender")),"conflict");
        assertThat(conflict.statusCode()).isEqualTo(409);assertThat(json.readTree(conflict.body()).path("code").asText()).isEqualTo("SEAT_UNAVAILABLE");
        assertThat(call("GET","/api/bookings/"+UUID.randomUUID(),null,null).statusCode()).isEqualTo(404);
        assertThat(call("GET","/health",null,null).statusCode()).isEqualTo(200);
        assertThat(call("GET","/actuator/health",null,null).statusCode()).isEqualTo(200);
    }
}

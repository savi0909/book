package com.example.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import static com.example.booking.MovieModels.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "lab.maintenance-enabled=false","movie.payment-worker-enabled=false","movie.payment-controls-enabled=true"})
class MovieBookingIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired MovieCatalog catalog;
    @Autowired MovieBookingService bookings;
    @Autowired MoviePaymentService payments;
    @Autowired MoviePaymentMock provider;
    @Autowired MovieStore store;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newHttpClient();
    Multiplex multiplex(int count,int capacity,Map<Category,Integer> categories) {
        var screens=new ArrayList<ScreenRequest>();
        for(int n=1;n<=count;n++) screens.add(new ScreenRequest("Screen "+n,capacity,categories));
        return catalog.createMultiplex(new MultiplexRequest("Cinema "+UUID.randomUUID(),"Asia/Kolkata",screens));
    }
    Show show(Multiplex multiplex,int screen,Instant start) {
        var movie=catalog.createMovie(new MovieRequest("Movie "+UUID.randomUUID(),"Hindi",120));
        var categories=multiplex.screens().get(screen).categorySeats().keySet();
        Map<Category,Integer> prices=new EnumMap<>(Category.class);
        for(var category:categories) prices.put(category,switch(category) {case A->50000;case B->30000;case C->15000;});
        return catalog.createShow(new ShowRequest(movie.id(),multiplex.screens().get(screen).id(),start,15,prices));
    }
    Show show() { return show(multiplex(5,200,null),0,Instant.now().plusSeconds(86400)); }
    Booking hold(Show show,List<Integer> seats) { return bookings.hold(new HoldRequest(show.id(),seats,"buyer-"+UUID.randomUUID(),120),"hold-"+UUID.randomUUID()); }
    Checkout checkout(Booking booking,String key,int bucket) { return payments.checkout(booking.id(),key,new CheckoutRequest(0,bucket)); }
    void expire(UUID booking) { store.db().jdbc().update("UPDATE movie_bookings SET expires_at=clock_timestamp()-interval '1 millisecond' WHERE id=?",booking); }
    <T> List<T> race(int count,IntFunction<T> work) throws Exception {
        try(var pool=Executors.newFixedThreadPool(count)) {
            var start=new CountDownLatch(1);List<Future<T>> futures=new ArrayList<>();
            for(int n=0;n<count;n++) {int index=n;futures.add(pool.submit(()->{start.await();return work.apply(index);}));}
            start.countDown();var results=new ArrayList<T>();
            for(var future:futures) results.add(future.get(20,TimeUnit.SECONDS));
            return results;
        }
    }
    HttpResponse<String> call(String method,String path,Object body,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");
        if(key!=null) builder.header("Idempotency-Key",key);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void categoryCapacityAndRoundingSupportTwoAndThreeCategories() {
        var three=multiplex(5,201,null);
        assertThat(three.screens().getFirst().categorySeats()).containsExactlyInAnyOrderEntriesOf(Map.of(Category.A,20,Category.B,40,Category.C,141));
        var two=multiplex(100,500,Map.of(Category.A,30,Category.C,70));
        assertThat(two.screens()).hasSize(100);
        assertThat(two.screens().getFirst().categorySeats()).containsExactlyInAnyOrderEntriesOf(Map.of(Category.A,150,Category.C,350));
        assertThatThrownBy(()->multiplex(5,200,Map.of(Category.A,10,Category.B,20,Category.C,60))).isInstanceOf(ApiException.class).hasMessageContaining("adding to 100");
    }
    @Test void screenAndSeatBoundsAreValidatedAtHttpBoundary() throws Exception {
        var four=new MultiplexRequest("invalid","Asia/Kolkata",Collections.nCopies(4,new ScreenRequest("s",200,null)));
        assertThat(call("POST","/api/demo/movie/multiplexes",four,null).statusCode()).isEqualTo(400);
        var tooLarge=new MultiplexRequest("invalid","Asia/Kolkata",List.of(new ScreenRequest("s1",501,null),new ScreenRequest("s2",200,null),new ScreenRequest("s3",200,null),new ScreenRequest("s4",200,null),new ScreenRequest("s5",200,null)));
        assertThat(call("POST","/api/demo/movie/multiplexes",tooLarge,null).statusCode()).isEqualTo(400);
        var show=show();
        assertThat(call("POST","/api/movie/holds",new HoldRequest(show.id(),List.of(1,1),"buyer",120),"duplicates").statusCode()).isEqualTo(400);
        assertThat(call("POST","/api/movie/holds",new HoldRequest(show.id(),List.of(201),"buyer",120),"missing").statusCode()).isEqualTo(404);
    }
    @Test void atomicGroupHoldPricesDifferentCategoriesAndConflictRollsBackEverything() {
        var show=show();var winner=hold(show,List.of(61,1,21));
        assertThat(winner.seats()).extracting(SelectedSeat::seatNumber).containsExactly(1,21,61);
        assertThat(winner.amountMinor()).isEqualTo(95000);
        assertThatThrownBy(()->hold(show,List.of(2,21,62))).isInstanceOf(ApiException.class).hasMessageContaining("no seats");
        var seats=catalog.seats(show.id(),500,0);
        assertThat(seats.get(1).availability()).isEqualTo("AVAILABLE");
        assertThat(seats.get(61).availability()).isEqualTo("AVAILABLE");
        assertThat(store.db().jdbc().queryForObject("SELECT count(*) FROM movie_bookings WHERE show_id=?",Integer.class,show.id())).isEqualTo(1);
    }
    @Test void reversedOverlappingSeatOrdersHaveOneWinnerWithoutDeadlock() throws Exception {
        var show=show();
        var results=race(16,n->{try{return hold(show,n%2==0?List.of(1,2,3):List.of(3,2,1)).state();}
            catch(ApiException e){assertThat(e.code()).isEqualTo("SEAT_UNAVAILABLE");return "CONFLICT";}});
        assertThat(results).filteredOn("HELD"::equals).hasSize(1);
        assertThat(catalog.seats(show.id(),500,0).stream().filter(s->s.availability().equals("HELD"))).hasSize(3);
    }
    @Test void sameSeatsAcrossShowsAreIndependentAndDateQueryUsesMultiplexZone() {
        var multiplex=multiplex(5,500,null);
        var date=LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2);
        var first=show(multiplex,0,date.atTime(0,30).atZone(ZoneId.of("Asia/Kolkata")).toInstant());
        var second=show(multiplex,0,date.atTime(4,0).atZone(ZoneId.of("Asia/Kolkata")).toInstant());
        assertThat(hold(first,List.of(500)).seats().getFirst().seatNumber()).isEqualTo(500);
        assertThat(hold(second,List.of(500)).state()).isEqualTo("HELD");
        assertThat(catalog.shows(multiplex.id(),date,100,0)).extracting(Show::id).containsExactly(first.id(),second.id());
        assertThat(catalog.shows(multiplex.id(),date.minusDays(1),100,0)).isEmpty();
    }
    @Test void concurrentOverlappingShowScheduleHasOneWinnerAndTurnaroundIsReserved() throws Exception {
        var multiplex=multiplex(5,200,null);var movie=catalog.createMovie(new MovieRequest("Schedule","English",120));
        var start=Instant.now().plusSeconds(86400);
        var request=new ShowRequest(movie.id(),multiplex.screens().getFirst().id(),start,15,Map.of(Category.A,50000,Category.B,30000,Category.C,15000));
        var results=race(8,n->{try{return catalog.createShow(request).id().toString();}catch(ApiException e){assertThat(e.code()).isEqualTo("SCREEN_SCHEDULE_OVERLAP");return "CONFLICT";}});
        assertThat(results).filteredOn(s->!s.equals("CONFLICT")).hasSize(1);
        assertThatThrownBy(()->catalog.createShow(new ShowRequest(movie.id(),request.screenId(),start.plusSeconds(7200),15,request.pricesMinor())))
            .isInstanceOf(ApiException.class).hasMessageContaining("turnaround");
        assertThat(catalog.createShow(new ShowRequest(movie.id(),request.screenId(),start.plusSeconds(8100),15,request.pricesMinor())).id()).isNotNull();
    }
    @Test void concurrentSameHoldKeyHasOneGroupAndSeatOrderIsCanonical() throws Exception {
        var show=show();String key="same-"+UUID.randomUUID();String buyer="buyer-"+UUID.randomUUID();
        var results=race(12,n->bookings.hold(new HoldRequest(show.id(),n%2==0?List.of(1,2):List.of(2,1),buyer,120),key));
        assertThat(results.stream().map(Booking::id).distinct()).hasSize(1);
        assertThat(results).filteredOn(b->!b.replayed()).hasSize(1);
        assertThatThrownBy(()->bookings.hold(new HoldRequest(show.id(),List.of(1,3),buyer,120),key)).isInstanceOf(ApiException.class);
    }
    @Test void firstCallSuccessConfirmsEverySelectedSeatAndReplaysCannotDispatch() throws Exception {
        var show=show();var booking=hold(show,List.of(1,21,61));String key="first";
        var results=race(8,n->checkout(booking,key,0));
        assertThat(results.stream().map(r->r.payment().id()).distinct()).hasSize(1);
        UUID payment=results.getFirst().payment().id();
        var done=payments.reconcile(payment);
        assertThat(done.state()).isEqualTo("CONFIRMED");assertThat(done.payment().dispatches()).isEqualTo(1);
        assertThat(done.seats()).hasSize(3);
        assertThat(checkout(booking,key,0).replayed()).isTrue();
        assertThat(payments.reconcile(payment).payment().dispatches()).isEqualTo(1);
        assertThat(catalog.seats(show.id(),500,0)).filteredOn(s->s.availability().equals("BOOKED")).hasSize(3);
    }
    @Test void retrySuccessUsesSamePaymentAndImmutableLogicalCallReceipts() {
        var booking=hold(show(),List.of(1,2));var payment=checkout(booking,"retry",9500).payment();
        var waiting=payments.reconcile(payment.id());
        assertThat(waiting.state()).isEqualTo("PAYMENT_PENDING");assertThat(waiting.payment().state()).isEqualTo("RETRY_PENDING");
        var done=payments.reconcile(payment.id());
        assertThat(done.state()).isEqualTo("CONFIRMED");assertThat(done.payment().id()).isEqualTo(payment.id());assertThat(done.payment().dispatches()).isEqualTo(2);
        assertThat(store.db().jdbc().queryForObject("SELECT count(*) FROM movie_provider_receipts WHERE payment_id=?",Integer.class,payment.id())).isEqualTo(2);
    }
    @Test void failedSecondCallKeepsWholeHoldAndFreshPaymentDoesNotResetDeadlineOrHistory() {
        var show=show();var booking=hold(show,List.of(1,2));var first=checkout(booking,"failed",9950);
        payments.reconcile(first.payment().id());var failed=payments.reconcile(first.payment().id());
        assertThat(failed.state()).isEqualTo("HELD");assertThat(failed.expiresAt()).isEqualTo(booking.expiresAt());assertThat(failed.payment().state()).isEqualTo("FAILED");
        assertThatThrownBy(()->hold(show,List.of(2,3))).isInstanceOf(ApiException.class);
        var next=checkout(booking,"new-payment",0);
        assertThat(next.payment().id()).isNotEqualTo(first.payment().id());assertThat(next.payment().paymentNumber()).isEqualTo(2);
        assertThat(checkout(booking,"failed",9950).payment().id()).isEqualTo(first.payment().id());
        assertThat(payments.reconcile(next.payment().id()).state()).isEqualTo("CONFIRMED");
        assertThat(payments.history(booking.id())).extracting(Payment::state).containsExactly("FAILED","SUCCEEDED");
        assertThat(bookings.get(booking.id()).expiresAt()).isEqualTo(booking.expiresAt());
    }
    @Test void expiredGroupIsReassignedAndLateSuccessCannotStealAnySeat() {
        var show=show();var old=hold(show,List.of(1,2));var payment=checkout(old,"late",0).payment();expire(old.id());
        var replacement=hold(show,List.of(1,2));var late=payments.reconcile(payment.id());
        assertThat(late.state()).isEqualTo("EXPIRED");assertThat(late.payment().refundRequired()).isTrue();
        assertThat(bookings.get(replacement.id()).state()).isEqualTo("HELD");
        assertThat(store.db().jdbc().queryForList("SELECT DISTINCT active_booking_id FROM movie_show_seats WHERE show_id=? AND seat_number IN (1,2)",UUID.class,show.id())).containsExactly(replacement.id());
        assertThatThrownBy(()->checkout(old,"cannot-restart",0)).isInstanceOf(ApiException.class);
    }
    @Test void acceptanceBeforeLostResponseReclaimsSameLogicalReceiptAndStaleTokenCannotApply() {
        var booking=hold(show(),List.of(1,2));var payment=checkout(booking,"lost",0).payment();UUID old=UUID.randomUUID();
        store.db().jdbc().update("UPDATE movie_payments SET lease_token=?,lease_until=clock_timestamp()+interval '5 seconds',dispatches=1 WHERE id=?",old,payment.id());
        assertThat(provider.accept(payment.id(),0,1)).isEqualTo("SUCCESS");
        assertThat(payments.reconcile(payment.id()).state()).isEqualTo("PAYMENT_PENDING");
        store.db().jdbc().update("UPDATE movie_payments SET lease_until=clock_timestamp()-interval '1 millisecond' WHERE id=?",payment.id());
        assertThat(payments.reconcile(payment.id()).state()).isEqualTo("CONFIRMED");
        payments.apply(payment.id(),old,"SUCCESS");
        assertThat(payments.get(payment.id()).dispatches()).isEqualTo(2);
        assertThat(store.db().jdbc().queryForObject("SELECT count(*) FROM movie_provider_receipts WHERE payment_id=?",Integer.class,payment.id())).isEqualTo(1);
        assertThat(bookings.audit(booking.id())).filteredOn(m->m.get("action").equals("CONFIRMED")).hasSize(1);
    }
    @Test void cancellationReleasesEveryMemberAndConfirmedCancellationRequiresRefund() {
        var show=show();var booking=hold(show,List.of(1,2));var payment=checkout(booking,"paid",0).payment();payments.reconcile(payment.id());
        var cancelled=bookings.cancel(booking.id());assertThat(cancelled.state()).isEqualTo("CANCELLED");assertThat(cancelled.payment().refundRequired()).isTrue();
        assertThat(bookings.cancel(booking.id()).replayed()).isTrue();assertThat(hold(show,List.of(1,2)).state()).isEqualTo("HELD");
    }
}

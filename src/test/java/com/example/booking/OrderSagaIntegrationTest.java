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
import java.time.Instant;
import java.util.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import static com.example.booking.MovieModels.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "lab.maintenance-enabled=false","movie.payment-worker-enabled=false",
    "order.enabled=true","order.worker-enabled=false","order.kafka-enabled=false","order.api-token=saga-test"})
class OrderSagaIntegrationTest {
    static HttpServer providerStub;
    static OrderStore stubStore;
    static {
        try {
            providerStub=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            providerStub.createContext("/mock-pg/saga/operations/",exchange->{
                try {
                    String operation=exchange.getRequestURI().getPath().substring("/mock-pg/saga/operations/".length());
                    var jdbc=stubStore.db.jdbc();
                    var rows=jdbc.queryForList("SELECT * FROM order_provider_receipts WHERE provider_key=?",operation);
                    if(rows.isEmpty() && exchange.getRequestMethod().equals("GET")){exchange.sendResponseHeaders(404,-1);return;}
                    if(rows.isEmpty()) {
                        var body=stubStore.json.readTree(exchange.getRequestBody());long amount=body.path("amountMinor").asLong();
                        boolean refund=body.path("operationType").asText().equals("REFUND");
                        String result=refund?(amount%100==11?"REJECTED":"REFUNDED"):(amount%100==99?"DECLINED":amount%100==55?"ACTION_REQUIRED":"CAPTURED");
                        jdbc.update("""
                            INSERT INTO order_provider_receipts(provider_key,request_hash,operation_type,order_id,amount_minor,provider_id,outcome)
                            VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                            """,operation,OrderStore.hash(body.toString()),body.path("operationType").asText(),UUID.fromString(body.path("orderId").asText()),amount,"mock-"+operation,result);
                        rows=jdbc.queryForList("SELECT * FROM order_provider_receipts WHERE provider_key=?",operation);
                    }
                    var row=rows.getFirst();byte[] bytes=stubStore.encode(Map.of("operationId",operation,"orderId",row.get("order_id"),"operationType",row.get("operation_type"),
                        "amountMinor",row.get("amount_minor"),"currency","INR","status",row.get("outcome"),"providerId",row.get("provider_id"))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                } catch(Exception e){exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}
            });providerStub.start();
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("order.provider-url",()->"http://127.0.0.1:"+providerStub.getAddress().getPort());
    }
    @Autowired MovieCatalog catalog;
    @Autowired ObjectMapper json;
    @Autowired OrderStore store;
    @Autowired OrderService orders;
    @Autowired OrderInventory inventory;
    @Autowired OrderPayment payments;
    @Autowired OrderRelay relay;
    @Autowired MovieBookingService legacyBookings;
    @Autowired MoviePaymentService legacyPayments;
    @BeforeEach void providerStore(){
        stubStore=store;
        store.db.jdbc().execute("""
            CREATE TABLE IF NOT EXISTS order_provider_receipts (
              provider_key text PRIMARY KEY,request_hash text NOT NULL,operation_type text NOT NULL,
              order_id uuid NOT NULL,amount_minor bigint NOT NULL,provider_id text NOT NULL,outcome text NOT NULL)
            """);
    }
    @AfterAll static void stopProvider(){providerStub.stop(0);}
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newHttpClient();
    Show show() {
        var screens=new ArrayList<ScreenRequest>();
        for(int n=0;n<5;n++) screens.add(new ScreenRequest("s"+n,200,null));
        var mx=catalog.createMultiplex(new MultiplexRequest("saga-"+UUID.randomUUID(),"Asia/Kolkata",screens));
        var movie=catalog.createMovie(new MovieRequest("Saga","English",100));
        return catalog.createShow(new ShowRequest(movie.id(),mx.screens().getFirst().id(),Instant.now().plusSeconds(3600),15,
            Map.of(Category.A,50000,Category.B,30000,Category.C,15000)));
    }
    HttpResponse<String> request(String method,String path,UUID customer,String key,Object body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json")
            .header("Authorization","Bearer saga-test").header("X-Customer-Id",customer.toString());
        if(key!=null)b.header("Idempotency-Key",key);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void createsAnAcceptedOrderWithServerPriceAndStoredReplay() throws Exception {
        var show=show();var customer=UUID.randomUUID();String key=UUID.randomUUID().toString();
        var body=Map.of("showId",show.id(),"seatNumbers",List.of(21,1));
        var first=request("POST","/api/orders",customer,key,body);
        assertThat(first.statusCode()).isEqualTo(202);
        var accepted=json.readTree(first.body());
        assertThat(request("POST","/api/orders",customer,key,Map.of("showId",show.id(),"seatNumbers",List.of(1,21))).body()).isEqualTo(first.body());
        var view=request("GET",accepted.path("statusUrl").asText(),customer,null,null);
        assertThat(view.statusCode()).isEqualTo(200);
        assertThat(json.readTree(view.body()).path("amountMinor").asLong()).isEqualTo(80000);
        assertThat(request("POST","/api/orders",customer,key,Map.of("showId",show.id(),"seatNumbers",List.of(2))).statusCode()).isEqualTo(409);
        assertThat(request("GET",accepted.path("statusUrl").asText(),UUID.randomUUID(),null,null).statusCode()).isEqualTo(404);
    }
    UUID create(UUID customer,Show show,List<Integer> seats) {
        return UUID.fromString(store.node(orders.create(customer,UUID.randomUUID().toString(),new OrderMessages.Create(show.id(),seats))).path("orderId").asText());
    }
    @Test void localTransitionAndOutgoingMessagesRollbackTogether() {
        var show=show();var customer=UUID.randomUUID();
        assertThatThrownBy(()->store.db.tx(()->{
            create(customer,show,List.of(1));throw new IllegalStateException("crash before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM orders WHERE customer_id=?",Integer.class,customer)).isZero();
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM order_requests WHERE customer_id=?",Integer.class,customer)).isZero();
    }
    @Test void concurrentSameKeyCannotLeaveOrphanOrders() throws Exception {
        var customer=UUID.randomUUID();var show=show();String key=UUID.randomUUID().toString();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var jobs=new ArrayList<java.util.concurrent.Future<String>>();
            for(int i=0;i<8;i++)jobs.add(executor.submit(()->orders.create(customer,key,new OrderMessages.Create(show.id(),List.of(1,2)))));
            var responses=new HashSet<String>();for(var job:jobs)responses.add(job.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(responses).hasSize(1);
        }
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM orders WHERE customer_id=?",Integer.class,customer)).isEqualTo(1);
    }
    OrderMessages.Envelope message(UUID order,String type) {
        return store.decode(store.db.jdbc().queryForObject("SELECT payload::text FROM order_outbox WHERE aggregate_id=? AND event_type=? ORDER BY created_at DESC LIMIT 1",String.class,order,type));
    }
    void deliver(OrderMessages.Envelope e) {
        if(e.type().equals("InventoryResult") || e.type().equals("PaymentResult"))orders.consume(e);
        else if(Set.of("ReserveInventory","CommitReservation","ReleaseReservation","CancelBooking","QueryInventory").contains(e.type()))inventory.consume(e);
        else payments.consume(e);
        store.db.jdbc().update("UPDATE order_outbox SET status='PUBLISHED',published_at=clock_timestamp(),locked_by=NULL,lock_token=NULL,locked_until=NULL WHERE id=?",e.messageId());
    }
    void pump(UUID order) {
        for(int n=0;n<20;n++) {
            var pending=store.db.jdbc().queryForList("SELECT payload::text FROM order_outbox WHERE aggregate_id=? AND status='PENDING' AND destination<>? ORDER BY created_at,id",String.class,order,OrderMessages.EVENTS);
            for(var payload:pending)deliver(store.decode(payload));
            payments.tick();
        }
    }
    String status(UUID order){return store.order(order,false).get("status").toString();}
    @Test void happyPathRequiresPaymentAndCommittedInventoryAndDuplicatesAreHarmless() {
        var customer=UUID.randomUUID();UUID order=create(customer,show(),List.of(1,2));
        deliver(message(order,"ReserveInventory"));deliver(message(order,"InventoryResult"));
        assertThat(status(order)).isEqualTo("PENDING");
        assertThat(store.saga(order,false).get("phase")).isEqualTo("PAYING");
        pump(order);
        assertThat(status(order)).isEqualTo("CONFIRMED");
        var late=message(order,"InventoryResult");orders.consume(late);orders.consume(late);
        assertThat(status(order)).isEqualTo("CONFIRMED");
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM order_payment_operations WHERE order_id=? AND operation_type='CAPTURE'",Integer.class,order)).isEqualTo(1);
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM order_outbox WHERE aggregate_id=? AND event_type='OrderConfirmed'",Integer.class,order)).isEqualTo(1);
        assertThatThrownBy(()->orders.cancel(customer,order)).isInstanceOf(ApiException.class);
    }
    @Test void cancellationBeforeReserveAndCapturePersistsTombstones() {
        var customer=UUID.randomUUID();UUID order=create(customer,show(),List.of(1));var reserve=message(order,"ReserveInventory");
        orders.cancel(customer,order);
        deliver(message(order,"CancelPayment"));deliver(message(order,"ReleaseReservation"));
        deliver(reserve);pump(order);
        assertThat(status(order)).isEqualTo("CANCELLED");
        assertThat(store.db.jdbc().queryForObject("SELECT booking_id FROM order_inventory WHERE order_id=?",UUID.class,order)).isNull();
        assertThat(store.db.jdbc().queryForObject("SELECT status FROM order_payment_operations WHERE order_id=?",String.class,order)).isEqualTo("CANCELLED");
    }
    @Test void commitFailureAfterChargeRefundsAndReleasesBeforeFailure() {
        var customer=UUID.randomUUID();UUID order=create(customer,show(),List.of(1));
        deliver(message(order,"ReserveInventory"));deliver(message(order,"InventoryResult"));deliver(message(order,"CapturePayment"));payments.tick();
        var paid=store.db.jdbc().queryForList("SELECT payload::text FROM order_outbox WHERE aggregate_id=? AND event_type='PaymentResult' ORDER BY created_at",String.class,order);
        paid.forEach(p->deliver(store.decode(p)));
        UUID reservation=(UUID)store.saga(order,false).get("reservation_id");
        store.db.jdbc().update("UPDATE movie_bookings SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",reservation);
        pump(order);
        assertThat(status(order)).isEqualTo("FAILED");
        assertThat(store.saga(order,false).get("payment_state")).isEqualTo("REFUNDED");
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM movie_show_seats WHERE active_booking_id=?",Integer.class,reservation)).isZero();
    }
    @Test void lateCaptureAfterCancellationRefundsWithoutRevival() {
        var customer=UUID.randomUUID();UUID order=create(customer,show(),List.of(1));
        deliver(message(order,"ReserveInventory"));deliver(message(order,"InventoryResult"));deliver(message(order,"CapturePayment"));payments.tick();
        orders.cancel(customer,order);pump(order);
        assertThat(status(order)).isEqualTo("CANCELLED");
        assertThat(store.saga(order,false).get("payment_state")).isEqualTo("REFUNDED");
        var capture=message(order,"CapturePayment");
        var duplicate=new OrderMessages.Envelope(UUID.randomUUID(),capture.type(),1,order,capture.sagaId(),capture.commandId(),capture.correlationId(),null,Instant.now(),capture.payload());
        payments.consume(duplicate);pump(order);
        assertThat(status(order)).isEqualTo("CANCELLED");
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM order_provider_receipts WHERE order_id=?",Integer.class,order)).isEqualTo(2);
    }
    @Test void lostCommitReplyReconcilesInsteadOfRefundingCommittedBooking() {
        var customer=UUID.randomUUID();UUID order=create(customer,show(),List.of(1));
        deliver(message(order,"ReserveInventory"));deliver(message(order,"InventoryResult"));deliver(message(order,"CapturePayment"));payments.tick();
        store.db.jdbc().queryForList("SELECT payload::text FROM order_outbox WHERE aggregate_id=? AND event_type='PaymentResult' ORDER BY created_at",String.class,order).forEach(p->deliver(store.decode(p)));
        deliver(message(order,"CommitReservation"));
        assertThat(status(order)).isEqualTo("PENDING");
        store.db.jdbc().update("UPDATE order_outbox SET status='PUBLISHED' WHERE aggregate_id=? AND event_type='InventoryResult'",order);
        store.db.jdbc().update("UPDATE order_sagas SET next_action_at=clock_timestamp()-interval '1 second' WHERE order_id=?",order);
        orders.recover();pump(order);
        assertThat(status(order)).isEqualTo("CONFIRMED");
    }
    @Test void declineAndPermanentRefundFailureKeepDifferentBusinessOutcomes() {
        var customer=UUID.randomUUID();var show=show();
        store.db.jdbc().update("UPDATE movie_show_seats SET price_minor=49999 WHERE show_id=? AND seat_number=1",show.id());
        UUID declined=create(customer,show,List.of(1));pump(declined);assertThat(status(declined)).isEqualTo("FAILED");
        store.db.jdbc().update("UPDATE movie_show_seats SET price_minor=50011 WHERE show_id=? AND seat_number=2",show.id());
        UUID refundFails=create(customer,show,List.of(2));
        deliver(message(refundFails,"ReserveInventory"));deliver(message(refundFails,"InventoryResult"));deliver(message(refundFails,"CapturePayment"));payments.tick();
        orders.cancel(customer,refundFails);pump(refundFails);
        assertThat(status(refundFails)).isEqualTo("MANUAL_REVIEW");
        assertThat(store.saga(refundFails,false).get("next_action_at")).isNull();
    }
    @Test void competingOrdersShareMovieSeatLocksWithLegacyBookings() {
        var show=show();UUID a=create(UUID.randomUUID(),show,List.of(1)),b=create(UUID.randomUUID(),show,List.of(1));
        deliver(message(a,"ReserveInventory"));pump(b);pump(a);
        assertThat(status(a)).isEqualTo("CONFIRMED");assertThat(status(b)).isEqualTo("FAILED");
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM movie_bookings WHERE show_id=? AND state='CONFIRMED'",Integer.class,show.id())).isEqualTo(1);
    }
    @Test void expiredClaimsFenceOldTokensAndAuditedReplayRetainsIdentity() {
        UUID order=create(UUID.randomUUID(),show(),List.of(1));
        var claims=relay.claim(10);assertThat(claims).isNotEmpty();var old=claims.getFirst();
        store.db.jdbc().update("UPDATE order_outbox SET locked_until=clock_timestamp()-interval '1 second' WHERE id=?",old.id());
        assertThat(relay.heartbeat(old)).isFalse();assertThat(relay.acknowledge(old)).isFalse();assertThat(relay.fail(old,false,"late")).isFalse();
        relay.recoverExpired();assertThat(relay.acknowledge(old)).isFalse();
        // Permanent poison is DEAD, not a failed booking; replay is audited without changing payload/ID.
        var fresh=relay.claim(10).getFirst();String payload=store.db.jdbc().queryForObject("SELECT payload::text FROM order_outbox WHERE id=?",String.class,fresh.id());
        assertThat(relay.fail(fresh,true,"bad destination")).isTrue();
        assertThat(relay.replay(fresh.id(),"test-operator","fixed configuration")).isTrue();
        assertThat(store.db.jdbc().queryForObject("SELECT payload::text FROM order_outbox WHERE id=?",String.class,fresh.id())).isEqualTo(payload);
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM order_recovery_audit WHERE event_id=?",Integer.class,fresh.id())).isEqualTo(1);
        assertThat(status(order)).isEqualTo("PENDING");
    }
    @Test void staleUnknownCannotEraseAKnownReservation() {
        UUID order=create(UUID.randomUUID(),show(),List.of(1));
        store.db.jdbc().update("UPDATE order_sagas SET next_action_at=clock_timestamp()-interval '1 second' WHERE order_id=?",order);
        orders.recover();deliver(message(order,"QueryInventory"));var unknown=message(order,"InventoryResult");
        deliver(message(order,"ReserveInventory"));deliver(message(order,"InventoryResult"));
        assertThat(store.saga(order,false).get("inventory_state")).isEqualTo("HELD");
        deliver(unknown);
        assertThat(store.saga(order,false).get("inventory_state")).isEqualTo("HELD");
    }
    @Test void customerActionCannotBypassPaymentRecoveryBudget() {
        var show=show();store.db.jdbc().update("UPDATE movie_show_seats SET price_minor=50055 WHERE show_id=? AND seat_number=1",show.id());
        UUID order=create(UUID.randomUUID(),show,List.of(1));
        deliver(message(order,"ReserveInventory"));deliver(message(order,"InventoryResult"));deliver(message(order,"CapturePayment"));payments.tick();
        var op=store.db.jdbc().queryForMap("SELECT * FROM order_payment_operations WHERE order_id=?",order);
        store.db.jdbc().update("UPDATE order_payment_operations SET attempts=10,next_action_at=clock_timestamp()-interval '1 second' WHERE id=?",op.get("id"));
        payments.process((UUID)op.get("id"));
        assertThat(store.db.jdbc().queryForObject("SELECT status FROM order_payment_operations WHERE id=?",String.class,op.get("id"))).isEqualTo("MANUAL_REVIEW");
        assertThat(store.db.jdbc().queryForObject("SELECT next_action_at FROM order_payment_operations WHERE id=?",java.sql.Timestamp.class,op.get("id"))).isNull();
    }
    @Test void expiryBatchAdvancesPastFirstTwentyReservations() {
        var show=show();var ids=new ArrayList<UUID>();
        for(int n=1;n<=21;n++) {
            UUID order=create(UUID.randomUUID(),show,List.of(n));ids.add(order);deliver(message(order,"ReserveInventory"));
            UUID booking=store.db.jdbc().queryForObject("SELECT booking_id FROM order_inventory WHERE order_id=?",UUID.class,order);
            store.db.jdbc().update("UPDATE movie_bookings SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",booking);
            store.db.jdbc().update("UPDATE order_inventory SET expires_at=clock_timestamp()-interval '1 second' WHERE order_id=?",order);
        }
        inventory.expire();inventory.expire();inventory.expire();
        for(UUID id:ids)assertThat(store.db.jdbc().queryForObject("SELECT state FROM order_inventory WHERE order_id=?",String.class,id)).isEqualTo("EXPIRED");
    }
    @Test void concurrentInventoryParticipantsCannotOversell() throws Exception {
        var show=show();UUID a=create(UUID.randomUUID(),show,List.of(1,2)),b=create(UUID.randomUUID(),show,List.of(2,1));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var one=pool.submit(()->{start.await();inventory.consume(message(a,"ReserveInventory"));return true;});
            var two=pool.submit(()->{start.await();inventory.consume(message(b,"ReserveInventory"));return true;});start.countDown();
            one.get(10,java.util.concurrent.TimeUnit.SECONDS);two.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        pump(a);pump(b);
        assertThat(List.of(status(a),status(b))).containsExactlyInAnyOrder("CONFIRMED","FAILED");
    }
    @Test void cancellationAfterCommitBeforeReplyUsesCancelBookingAndIgnoresLateCommit() {
        var customer=UUID.randomUUID();UUID order=create(customer,show(),List.of(1));
        deliver(message(order,"ReserveInventory"));deliver(message(order,"InventoryResult"));deliver(message(order,"CapturePayment"));payments.tick();
        store.db.jdbc().queryForList("SELECT payload::text FROM order_outbox WHERE aggregate_id=? AND event_type='PaymentResult' ORDER BY created_at",String.class,order).forEach(p->deliver(store.decode(p)));
        deliver(message(order,"CommitReservation"));var committed=message(order,"InventoryResult");
        store.db.jdbc().update("UPDATE order_outbox SET status='PUBLISHED' WHERE id=?",committed.messageId());
        orders.cancel(customer,order);pump(order);deliver(committed);
        assertThat(status(order)).isEqualTo("CANCELLED");
        assertThat(store.saga(order,false).get("payment_state")).isEqualTo("REFUNDED");
        assertThat(store.db.jdbc().queryForObject("SELECT state FROM order_inventory WHERE order_id=?",String.class,order)).isEqualTo("RELEASED");
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM order_commands WHERE order_id=? AND type='CancelBooking'",Integer.class,order)).isEqualTo(1);
    }
    @Test void abandonedHundredRowBatchAndCompetingClaimersRecoverWithFreshFences() throws Exception {
        UUID order=create(UUID.randomUUID(),show(),List.of(1));var saga=store.saga(order,false);UUID sagaId=(UUID)saga.get("id");
        store.db.tx(()->{
            for(int n=0;n<100;n++) {
                UUID id=UUID.randomUUID();store.outbox(new OrderMessages.Envelope(id,"OrderCreated",1,order,sagaId,id,sagaId,null,Instant.now(),store.json.createObjectNode()),OrderMessages.EVENTS,"batch/"+id);
            }return null;
        });
        var ids=new HashSet<UUID>();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for(int n=0;n<25;n++) {
                var one=pool.submit(()->relay.claim(10));var two=pool.submit(()->relay.claim(10));
                for(var c:one.get())assertThat(ids.add(c.id())).isTrue();for(var c:two.get())assertThat(ids.add(c.id())).isTrue();
            }
        }
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM order_outbox WHERE aggregate_id=? AND status='IN_PROGRESS'",Integer.class,order)).isEqualTo(102);
        store.db.jdbc().update("UPDATE order_outbox SET locked_until=clock_timestamp()-interval '1 second' WHERE aggregate_id=? AND status='IN_PROGRESS'",order);
        relay.recoverExpired();relay.recoverExpired();
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM order_outbox WHERE aggregate_id=? AND status='PENDING' AND attempts=1",Integer.class,order)).isEqualTo(102);
    }
    @Test void committedProviderEffectSurvivesLostLocalCompletionAndOnlyOneChargeExists() throws Exception {
        UUID order=create(UUID.randomUUID(),show(),List.of(1));deliver(message(order,"ReserveInventory"));deliver(message(order,"InventoryResult"));deliver(message(order,"CapturePayment"));
        var op=store.db.jdbc().queryForMap("SELECT * FROM order_payment_operations WHERE order_id=?",order);UUID id=(UUID)op.get("id");
        var body=Map.of("orderId",order,"operationType","CAPTURE","amountMinor",op.get("amount_minor"),"currency","INR");
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+providerStub.getAddress().getPort()+"/mock-pg/saga/operations/"+id))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(store.encode(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        // Represents restart after provider commit, before Payment persisted its result.
        store.db.jdbc().update("UPDATE order_payment_operations SET status='UNKNOWN',attempts=1,next_action_at=clock_timestamp() WHERE id=?",id);
        payments.process(id);pump(order);
        assertThat(status(order)).isEqualTo("CONFIRMED");
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM order_provider_receipts WHERE order_id=? AND operation_type='CAPTURE'",Integer.class,order)).isEqualTo(1);
    }
    @Test void terminalManualReviewRequiresAuditedNewRecoveryGeneration() {
        UUID order=create(UUID.randomUUID(),show(),List.of(1));
        store.db.jdbc().update("UPDATE order_sagas SET recovery_attempts=10,next_action_at=clock_timestamp()-interval '1 second' WHERE order_id=?",order);
        orders.recover();assertThat(status(order)).isEqualTo("MANUAL_REVIEW");
        assertThat(store.saga(order,false).get("next_action_at")).isNull();
        orders.resume(order,"operator","verified provider and repaired Kafka");orders.recover();
        assertThat(store.saga(order,false).get("recovery_generation")).isEqualTo(1);
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM order_recovery_audit WHERE order_id=?",Integer.class,order)).isEqualTo(1);
    }
    @Test void readyQueryUsesPartialIndexWithRetainedPublishedBacklog() throws Exception {
        UUID order=create(UUID.randomUUID(),show(),List.of(1));UUID saga=(UUID)store.saga(order,false).get("id");
        String prefix=UUID.randomUUID().toString();
        store.db.jdbc().update("""
            INSERT INTO order_outbox(id,aggregate_id,saga_id,dedupe_key,event_type,destination,payload,status,published_at)
            SELECT md5(? || g::text)::uuid,?,?,? || g::text,'OrderCreated',?,'{}'::jsonb,'PUBLISHED',clock_timestamp()
            FROM generate_series(1,5000) g
            """,prefix,order,saga,prefix,OrderMessages.EVENTS);
        store.db.jdbc().execute("ANALYZE order_outbox");
        var lines=store.db.jdbc().queryForList("""
            EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM order_outbox
            WHERE status='PENDING' AND available_at<=clock_timestamp() AND attempts<10
            ORDER BY available_at,id LIMIT 20 FOR UPDATE SKIP LOCKED
            """,String.class);
        String plan=String.join(System.lineSeparator(),lines);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/order-saga-query-plan.txt"),plan);
        assertThat(plan).contains("order_outbox_ready_idx");
    }
    @Test void legacyServiceCannotChargeOrCancelASagaOwnedBooking() {
        UUID order=create(UUID.randomUUID(),show(),List.of(1));deliver(message(order,"ReserveInventory"));
        UUID booking=store.db.jdbc().queryForObject("SELECT booking_id FROM order_inventory WHERE order_id=?",UUID.class,order);
        assertThatThrownBy(()->legacyPayments.checkout(booking,"legacy-bypass",new CheckoutRequest(0,null)))
            .isInstanceOf(ApiException.class).hasMessageContaining("Order API");
        assertThatThrownBy(()->legacyBookings.cancel(booking)).isInstanceOf(ApiException.class).hasMessageContaining("Order API");
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM movie_payments WHERE booking_id=?",Integer.class,booking)).isZero();
        assertThat(legacyBookings.get(booking).state()).isEqualTo("HELD");
    }
    @Test void apiRequestHashDoesNotDependOnMapIterationAcrossReplicas() {
        var customer=UUID.randomUUID();var show=show();String key=UUID.randomUUID().toString();
        var sorted=store.json.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        var other=store.json.copy();
        var module=new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(Map.class,new com.fasterxml.jackson.databind.JsonSerializer<Map>() {
            @Override public void serialize(Map value,com.fasterxml.jackson.core.JsonGenerator out,
                com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                var reversed=new TreeMap<String,Object>(Comparator.reverseOrder());
                value.forEach((k,v)->reversed.put(k.toString(),v));out.writeStartObject();
                for(var entry:reversed.entrySet()){out.writeFieldName(entry.getKey());provider.defaultSerializeValue(entry.getValue(),out);}
                out.writeEndObject();
            }
        });other.registerModule(module);
        var a=new OrderService(new OrderStore(store.db,sorted),120,10,10);
        var b=new OrderService(new OrderStore(store.db,other),120,10,10);
        String accepted=a.create(customer,key,new OrderMessages.Create(show.id(),List.of(1,2)));
        assertThat(b.create(customer,key,new OrderMessages.Create(show.id(),List.of(2,1)))).isEqualTo(accepted);
    }
    @Test void manualReviewCannotResumeForwardWhileCompensationIsQueued() {
        UUID order=create(UUID.randomUUID(),show(),List.of(1));
        deliver(message(order,"ReserveInventory"));deliver(message(order,"InventoryResult"));deliver(message(order,"CapturePayment"));payments.tick();
        store.db.jdbc().queryForList("SELECT payload::text FROM order_outbox WHERE aggregate_id=? AND event_type='PaymentResult' ORDER BY created_at",String.class,order).forEach(p->deliver(store.decode(p)));
        deliver(message(order,"CommitReservation"));
        store.db.jdbc().update("UPDATE order_sagas SET recovery_attempts=10,next_action_at=clock_timestamp()-interval '1 second' WHERE order_id=?",order);
        orders.recover();assertThat(status(order)).isEqualTo("MANUAL_REVIEW");
        orders.resume(order,"operator","resume after inspecting unresolved obligations");
        assertThat(store.saga(order,false).get("phase")).isEqualTo("COMPENSATING");
        pump(order);
        assertThat(status(order)).isEqualTo("FAILED");
        assertThat(store.saga(order,false).get("payment_state")).isEqualTo("REFUNDED");
        assertThat(store.db.jdbc().queryForObject("SELECT state FROM order_inventory WHERE order_id=?",String.class,order)).isEqualTo("RELEASED");
    }
    @Test void retryJitterAndFinalClaimBudgetDoNotChangeBusinessOutcome() {
        UUID order=create(UUID.randomUUID(),show(),List.of(1));UUID event=message(order,"OrderCreated").messageId();
        store.db.jdbc().update("UPDATE order_outbox SET available_at=clock_timestamp()-interval '1 day' WHERE id=?",event);
        var first=relay.claim(1).getFirst();assertThat(first.id()).isEqualTo(event);
        Instant before=store.now();assertThat(relay.fail(first,false,"temporary send failure")).isTrue();
        var retry=store.db.jdbc().queryForMap("SELECT * FROM order_outbox WHERE id=?",event);
        assertThat(retry.get("status")).isEqualTo("PENDING");assertThat(retry.get("attempts")).isEqualTo(1);
        assertThat(((java.sql.Timestamp)retry.get("available_at")).toInstant()).isBetween(before,before.plusSeconds(2));
        // Seed nine completed claims to exercise the final retry budget without sleeping through backoff.
        store.db.jdbc().update("UPDATE order_outbox SET attempts=9,available_at=clock_timestamp()-interval '1 day' WHERE id=?",event);
        var last=relay.claim(1).getFirst();assertThat(last.id()).isEqualTo(event);assertThat(last.attempts()).isEqualTo(10);
        assertThat(relay.heartbeat(first)).isFalse();assertThat(relay.acknowledge(first)).isFalse();assertThat(relay.fail(first,false,"stale")).isFalse();
        assertThat(relay.fail(last,false,"retry budget exhausted")).isTrue();
        assertThat(store.db.jdbc().queryForObject("SELECT status FROM order_outbox WHERE id=?",String.class,event)).isEqualTo("DEAD");
        assertThat(status(order)).isEqualTo("PENDING");
    }
}

package com.example.booking;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static com.example.booking.MovieModels.*;
import static com.example.booking.OrderMessages.*;

@Testcontainers
@SpringBootTest(properties={"lab.maintenance-enabled=false","movie.payment-worker-enabled=false",
    "order.enabled=true","order.worker-enabled=false","order.kafka-enabled=true","order.provider-token=saga-provider-test"})
class OrderKafkaIntegrationTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final KafkaContainer KAFKA=new KafkaContainer("apache/kafka:3.9.1");
    static final int PROVIDER_PORT=port();
    static Process gateway;
    static int port(){try(var socket=new java.net.ServerSocket(0)){return socket.getLocalPort();}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);r.add("spring.datasource.password",PG::getPassword);
        r.add("order.kafka-bootstrap",KAFKA::getBootstrapServers);r.add("order.kafka-group",()->"test-"+UUID.randomUUID());
        r.add("order.provider-url",()->"http://127.0.0.1:"+PROVIDER_PORT);
    }
    @Autowired OrderStore store;
    @Autowired OrderService orders;
    @Autowired OrderRelay relay;
    @Autowired OrderKafka kafka;
    @Autowired OrderPayment payments;
    @Autowired MovieCatalog catalog;
    @BeforeAll static void startGateway() throws Exception {
        try(var admin=AdminClient.create(Map.of("bootstrap.servers",KAFKA.getBootstrapServers()))) {
            admin.createTopics(TOPICS.stream().map(t->new NewTopic(t,3,(short)1)).toList()).all().get(20,TimeUnit.SECONDS);
        }
        Path module=Path.of("mock-payment-gateway").toAbsolutePath();
        // Build the copied participant as an independent application, preserving its own POM/version.
        var command=new ArrayList<String>();
        if(System.getProperty("os.name").startsWith("Windows"))command.addAll(List.of("cmd.exe","/d","/c","mvn"));else command.add("mvn");
        command.addAll(List.of("-B","-ntp","-f",module.resolve("pom.xml").toString(),"-DskipTests","package"));
        var build=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(Path.of("target/order-gateway-build.log").toFile()).start();
        if(!build.waitFor(180,TimeUnit.SECONDS)){build.destroyForcibly();throw new IllegalStateException("Gateway build timed out");}
        assertThat(build.exitValue()).as("Copied gateway build; see target/order-gateway-build.log").isZero();
        gateway=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx192m","-jar",
            module.resolve("target/mock-payment-gateway-1.0.0-SNAPSHOT.jar").toString(),"--server.address=127.0.0.1","--server.port="+PROVIDER_PORT,
            "--spring.datasource.url="+PG.getJdbcUrl(),"--spring.datasource.username="+PG.getUsername(),"--spring.datasource.password="+PG.getPassword(),
            "--mock-pg.saga-token=saga-provider-test").redirectErrorStream(true).redirectOutput(Path.of("target/order-gateway-runtime.log").toFile()).start();
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        for(int n=0;n<300;n++) {
            if(!gateway.isAlive())throw new IllegalStateException("Gateway exited; see target/order-gateway-runtime.log");
            try {if(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+PROVIDER_PORT+"/mock-pg/health")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200)return;}
            catch(Exception starting) { /* startup is bounded below */ }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Gateway startup timed out");
    }
    @AfterAll static void stopGateway() throws Exception {
        if(gateway!=null){gateway.destroy();if(!gateway.waitFor(10,TimeUnit.SECONDS))gateway.destroyForcibly();}
    }
    Show show() {
        var screens=new ArrayList<ScreenRequest>();for(int n=0;n<5;n++)screens.add(new ScreenRequest("screen"+n,200,null));
        var mx=catalog.createMultiplex(new MultiplexRequest("kafka"+UUID.randomUUID(),"Asia/Kolkata",screens));
        var film=catalog.createMovie(new MovieRequest("Kafka saga","English",100));
        return catalog.createShow(new ShowRequest(film.id(),mx.screens().getFirst().id(),Instant.now().plusSeconds(3600),10,Map.of(Category.A,50000,Category.B,30000,Category.C,15000)));
    }
    UUID create(UUID customer,List<Integer> seats,Show show) {
        return UUID.fromString(store.node(orders.create(customer,UUID.randomUUID().toString(),new Create(show.id(),seats))).path("orderId").asText());
    }
    void converge(UUID order,String status) throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(40).toNanos();
        while(System.nanoTime()<until) {
            payments.tick();relay.dispatch();kafka.poll();
            if(store.order(order,false).get("status").equals(status))return;
        }
        assertThat(store.order(order,false).get("status")).isEqualTo(status);
    }
    @Test void kafkaAndCopiedGatewayConfirmWithDuplicatePublicationThenCancelPendingOrder() throws Exception {
        var customer=UUID.randomUUID();var show=show();UUID order=create(customer,List.of(1,2),show);
        var reserve=store.db.jdbc().queryForObject("SELECT payload::text FROM order_outbox WHERE aggregate_id=? AND event_type='ReserveInventory'",String.class,order);
        // Publish twice, modeling a crash after Kafka ack before outbox completion.
        kafka.publish(INVENTORY,store.decode(reserve));kafka.publish(INVENTORY,store.decode(reserve));
        converge(order,"CONFIRMED");
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM order_inventory WHERE order_id=?",Integer.class,order)).isEqualTo(1);
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM payment_processor.saga_operations WHERE order_id=? AND operation_type='CAPTURE'",Integer.class,order)).isEqualTo(1);
        UUID pending=create(customer,List.of(3),show);orders.cancel(customer,pending);converge(pending,"CANCELLED");
        assertThat(store.saga(pending,false).get("payment_state")).isEqualTo("CANCELLED");
        UUID paidPending=create(customer,List.of(4),show);
        for(int n=0;n<100;n++) {
            relay.dispatch();kafka.poll();payments.tick();
            if(Boolean.TRUE.equals(store.db.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM order_payment_operations WHERE order_id=? AND status='CAPTURED')",Boolean.class,paidPending)))break;
        }
        assertThat(store.order(paidPending,false).get("status")).isEqualTo("PENDING");
        orders.cancel(customer,paidPending);converge(paidPending,"CANCELLED");
        assertThat(store.saga(paidPending,false).get("payment_state")).isEqualTo("REFUNDED");
        assertThat(store.db.jdbc().queryForObject("SELECT count(*) FROM payment_processor.saga_operations WHERE order_id=?",Integer.class,paidPending)).isEqualTo(2);
    }
    @Test void malformedKafkaMessageIsDurablyQuarantinedBeforeOffsetAdvances() throws Exception {
        UUID order=create(UUID.randomUUID(),List.of(1),show());
        String valid=store.db.jdbc().queryForObject("SELECT payload::text FROM order_outbox WHERE aggregate_id=? AND event_type='ReserveInventory'",String.class,order);
        var props=new Properties();props.put("bootstrap.servers",KAFKA.getBootstrapServers());props.put("acks","all");
        props.put("key.serializer",StringSerializer.class);props.put("value.serializer",StringSerializer.class);
        try(var producer=new KafkaProducer<String,String>(props)){
            producer.send(new ProducerRecord<>(INVENTORY,0,"invalid-key","{bad-json")).get(10,TimeUnit.SECONDS);
            producer.send(new ProducerRecord<String,String>(INVENTORY,0,"tombstone",null)).get(10,TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>(INVENTORY,0,order.toString(),valid)).get(10,TimeUnit.SECONDS);
        }
        for(int n=0;n<60;n++) {
            kafka.poll();
            if(store.db.jdbc().queryForObject("SELECT count(*) FROM order_message_failures WHERE quarantined",Integer.class)>=2 &&
                store.db.jdbc().queryForObject("SELECT count(*) FROM order_inventory WHERE order_id=?",Integer.class,order)==1)return;
        }
        fail("Poison record was not quarantined");
    }
}

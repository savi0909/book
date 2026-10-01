package com.example.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static com.example.booking.Models.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"lab.maintenance-enabled=false", "lab.failure-controls-enabled=true"})
class FailoverIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired BookingService bookings;
    @Autowired BookingStore store;
    @Autowired AdmissionGate gate;
    @Autowired CheckoutResponseDelay delay;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();
    @AfterEach void resume() { gate.resume(); }

    HttpRequest request(String method, String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(12))
            .header("Content-Type","application/json")
            .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
    }
    HttpResponse<String> call(String method,String path,String body) throws Exception {
        return http.send(request(method,path,body),HttpResponse.BodyHandlers.ofString());
    }
    Booking hold() {
        Event e=bookings.createEvent(new EventRequest("failover-test-"+UUID.randomUUID(),1));
        return bookings.hold(new HoldRequest(e.id(),1,"buyer",120),UUID.randomUUID().toString());
    }
    HttpRequest checkout(Booking booking, int milliseconds) {
        return HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/bookings/"+booking.id()+"/checkout"))
            .timeout(Duration.ofSeconds(12)).header("Content-Type","application/json")
            .header("Idempotency-Key","checkout-"+booking.id())
            .header("X-Lab-Response-Delay-Ms",String.valueOf(milliseconds))
            .POST(HttpRequest.BodyPublishers.ofString("{\"scenario\":\"SUCCESS\",\"delayMs\":0}")).build();
    }
    @Test void drainRejectsBusinessTrafficButPreservesLivenessAndCanResume() throws Exception {
        assertThat(call("POST","/api/demo/failover/drain",null).statusCode()).isEqualTo(200);
        assertThat(call("GET","/actuator/health/readiness",null).statusCode()).isEqualTo(503);
        assertThat(call("GET","/actuator/health/liveness",null).statusCode()).isEqualTo(200);
        assertThat(call("GET","/health",null).statusCode()).isEqualTo(200);
        var blocked=call("POST","/api/demo/events","{\"name\":\"must-not-enter\",\"seatCount\":1}");
        assertThat(blocked.statusCode()).isEqualTo(503);
        assertThat(json.readTree(blocked.body()).get("code").asText()).isEqualTo("INSTANCE_DRAINING");
        assertThat(blocked.headers().firstValue("Retry-After")).contains("1");
        assertThat(gate.status().get("inFlight")).isEqualTo(0);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM events WHERE name='must-not-enter'",Integer.class)).isZero();
        assertThat(call("POST","/api/demo/failover/resume",null).statusCode()).isEqualTo(200);
        assertThat(call("GET","/actuator/health/readiness",null).statusCode()).isEqualTo(200);
        assertThat(call("GET","/api/events",null).headers().firstValue("X-Booking-Instance")).contains("local");
    }
    @Test void admittedCheckoutCommitsBeforeResponseDelayAndFinishesDuringDrain() throws Exception {
        Booking booking=hold();
        var response=http.sendAsync(checkout(booking,2000),HttpResponse.BodyHandlers.ofString());
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!delay.waitingBookings().contains(booking.id()) && System.nanoTime()<end) Thread.sleep(20);
        assertThat(delay.waitingBookings()).contains(booking.id());
        assertThat(response.isDone()).isFalse();
        // A separate DB operation sees the committed intent while the HTTP response is withheld.
        Booking committed=bookings.get(booking.id());
        assertThat(committed.state()).isEqualTo("CHECKOUT");
        UUID payment=committed.payment().id();
        gate.drain();
        assertThat(gate.status().get("inFlight")).isEqualTo(1);
        assertThat(call("GET","/api/events",null).statusCode()).isEqualTo(503);
        assertThat(response.get(8,TimeUnit.SECONDS).statusCode()).isEqualTo(202);
        assertThat(delay.waitingBookings()).isEmpty();
        assertThat(gate.status().get("inFlight")).isEqualTo(0);
        gate.resume();
        var replay=http.send(checkout(booking,0),HttpResponse.BodyHandlers.ofString());
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replay.body()).get("payment").get("id").asText()).isEqualTo(payment.toString());
    }
    @Test void invalidDelayDoesNotCreateCheckoutIntent() throws Exception {
        Booking booking=hold();
        for(int value:new int[]{-1,10001}) {
            assertThat(http.send(checkout(booking,value),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        }
        assertThat(bookings.get(booking.id()).payment()).isNull();
    }
    @Test void poisonMutationsAreAbsentByDefaultButDiagnosticsAvailable() throws Exception {
        assertThat(call("POST","/api/demo/recovery/tick","{}").statusCode()).isEqualTo(404);
        assertThat(call("GET","/api/recovery/status",null).statusCode()).isEqualTo(200);
        assertThat(call("POST","/api/demo/outbox/tick","{}").statusCode()).isEqualTo(404);
        assertThat(call("GET","/api/outbox/status",null).statusCode()).isEqualTo(200);
    }
}

package com.example.bookingload;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoadTesterTest {
    static final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    static final Map<String,Map<String,Object>> bookings=new ConcurrentHashMap<>();
    static final List<String> keys=new CopyOnWriteArrayList<>(),payloads=new CopyOnWriteArrayList<>();
    static final AtomicInteger posts=new AtomicInteger(),fixtures=new AtomicInteger();
    static final Path root=Path.of("target","http-tests-"+UUID.randomUUID());
    static final Path stop=root.resolve("STOP");
    static final ExecutorService serverThreads=Executors.newVirtualThreadPerTaskExecutor();
    static HttpServer stub;
    static volatile boolean loseFirst,conflict,retryBusy,slowBody;
    static volatile int delay;
    static {
        try {
            System.setProperty("jdk.httpclient.disableRetryConnect","true");
            System.setProperty("jdk.httpclient.enableAllMethodRetry","false");
            Files.createDirectories(root);
            stub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            stub.setExecutor(serverThreads);stub.createContext("/",LoadTesterTest::handle);stub.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("loadtest.target",() -> "http://127.0.0.1:"+stub.getAddress().getPort());
        r.add("loadtest.results-dir",() -> root.resolve("results").toString());
        r.add("loadtest.allow-local",() -> "true");r.add("loadtest.observer-stop-file",stop::toString);
    }
    @Autowired TestRestTemplate api;
    @Autowired LoadRunService service;
    @BeforeEach void reset() throws Exception {
        loseFirst=conflict=retryBusy=slowBody=false;delay=0;bookings.clear();keys.clear();payloads.clear();posts.set(0);fixtures.set(0);
        Files.writeString(Path.of(stop+".heartbeat"),Instant.now().toString());
    }
    @AfterAll static void shutdown() { stub.stop(0);serverThreads.shutdownNow(); }

    @Test void fixedScheduleUsesVirtualThreadsAndReportsTenFreshHolds() throws Exception {
        String id=start(Map.of("rate",10,"seconds",1,"seats",10));JsonNode result=await(id);
        assertEquals("COMPLETED",result.path("state").asText());
        JsonNode report=result.path("report");assertEquals(10,report.path("attempts").asInt());
        assertEquals(10,report.path("virtualThreadAttempts").asInt());assertEquals(10,report.path("outcomes").path("HELD").asInt());
        assertEquals(10,report.path("perSecond").path("0").path("attempts").asInt());
        assertEquals(1,fixtures.get());assertEquals(10,new HashSet<>(keys).size());
        assertTrue(report.path("httpLatencyMs").has("p95"));
        assertFalse(Files.readString(root.resolve("results").resolve(id).resolve("report.json")).contains("createdAt"));
    }
    @Test void lostCommittedResponseReplaysExactPayloadWithoutCreatingSecondBooking() throws Exception {
        loseFirst=true;String id=start(Map.of("rate",1,"seconds",1,"policy","IMMEDIATE","maxAttempts",4));
        JsonNode result=await(id);assertEquals(2,result.path("report").path("attempts").asInt());
        assertEquals(1,result.path("report").path("outcomes").path("REPLAYED").asInt());
        assertEquals(1,bookings.size());assertEquals(keys.getFirst(),keys.getLast());assertEquals(payloads.getFirst(),payloads.getLast());
    }
    @Test void unknownCommitHasSeparateOneTimeDiscoveryBudget() throws Exception {
        loseFirst=true;String id=start(Map.of("rate",1,"seconds",1));
        JsonNode first=await(id);assertEquals(1,first.path("report").path("outcomes").path("UNKNOWN").asInt());
        assertEquals(202,api.postForEntity("/load/runs/"+id+"/discover",null,String.class).getStatusCode().value());
        JsonNode recovered=await(id);assertEquals(1,recovered.path("report").path("attempts").asInt());
        assertEquals(1,recovered.path("report").path("discoveryAttempts").asInt());
        assertEquals(1,recovered.path("report").path("outcomes").path("REPLAYED").asInt());
        assertEquals(409,api.postForEntity("/load/runs/"+id+"/discover",null,String.class).getStatusCode().value());
        assertEquals(1,bookings.size());
    }
    @Test void seatConflictIsTerminalEvenWithRetryPolicy() throws Exception {
        conflict=true;String id=start(Map.of("rate",1,"seconds",1,"policy","JITTER","maxAttempts",4));
        JsonNode result=await(id);assertEquals(1,posts.get());
        assertEquals(1,result.path("report").path("outcomes").path("CONFLICT").asInt());
    }
    @Test void retryAfterIsHonoredAndDoesNotFitShortDeadline() throws Exception {
        retryBusy=true;String id=start(Map.of("rate",1,"seconds",1,"policy","IMMEDIATE","maxAttempts",4,"deadlineMs",500));
        JsonNode result=await(id);assertEquals(1,posts.get());
        assertEquals(1,result.path("report").path("outcomes").path("UNKNOWN").asInt());
    }
    @Test void saturationStopsArrivalsWithoutBuildingAnUnboundedQueue() throws Exception {
        delay=400;String id=start(Map.of("rate",10,"seconds",1,"concurrency",1));JsonNode result=await(id);
        assertEquals("STOPPED",result.path("state").asText());assertEquals("GENERATOR_SATURATED",result.path("stopReason").asText());
        assertEquals(1,result.path("report").path("maxInFlight").asInt());assertEquals(1,posts.get());
        assertEquals(9,result.path("report").path("outcomes").path("NOT_STARTED").asInt());
    }
    @Test void overlappingRunRejectedAndStopDrainsAdmittedCall() throws Exception {
        delay=400;String id=start(Map.of("rate",10,"seconds",1));
        assertEquals(409,api.postForEntity("/load/runs",Map.of("rate",1,"seconds",1),String.class).getStatusCode().value());
        api.postForEntity("/load/runs/"+id+"/stop",null,String.class);
        JsonNode result=await(id);assertEquals("STOPPED",result.path("state").asText());assertEquals(0,result.path("inFlight").asInt());
    }
    @Test void availabilityModeSendsOnlyReadsAfterOneFixture() throws Exception {
        String id=start(Map.of("mode","AVAILABILITY","rate",2,"seconds",1));JsonNode result=await(id);
        assertEquals(0,posts.get());assertEquals(2,result.path("report").path("outcomes").path("READ_OK").asInt());
        assertTrue(result.path("report").path("kinds").has("AVAILABILITY"));
    }
    @Test void localBoundAndUnknownPropertyValidationHappenBeforeSetup() {
        assertEquals(400,api.postForEntity("/load/runs",Map.of("rate",11,"seconds",1),String.class).getStatusCode().value());
        assertEquals(400,api.postForEntity("/load/runs",Map.of("typo",10),String.class).getStatusCode().value());
        assertEquals(0,fixtures.get());
    }
    @Test void wholeBodyTimeoutLeavesCommitUnknownWithoutOverlappingCalls() throws Exception {
        slowBody=true;String id=start(Map.of("rate",1,"seconds",1,"timeoutMs",100));JsonNode result=await(id);
        assertEquals(1,result.path("report").path("attempts").asInt());assertEquals(1,posts.get());
        assertEquals(1,result.path("report").path("outcomes").path("UNKNOWN").asInt());
    }
    @Test void jitterIsSeededAndServerFloorAppliesToBothPolicies() {
        long jitter=LoadRunService.retryDelay(RunSpec.Policy.JITTER,1,null,new Random(9),Instant.now());
        assertEquals(jitter,LoadRunService.retryDelay(RunSpec.Policy.JITTER,1,null,new Random(9),Instant.now()));
        assertTrue(jitter>=0 && jitter<=500);
        assertEquals(1000,LoadRunService.retryDelay(RunSpec.Policy.IMMEDIATE,1,"1",new Random(9),Instant.now()));
        assertEquals(1000,LoadRunService.retryDelay(RunSpec.Policy.JITTER,1,"1",new Random(9),Instant.now()));
        assertEquals(Long.MAX_VALUE,LoadRunService.retryDelay(RunSpec.Policy.JITTER,1,"invalid",new Random(9),Instant.now()));
    }
    @Test void observerMissingOrExpiredAndLocalTargetScopeAreRejected() {
        LoaderSettings settings=new LoaderSettings(java.net.URI.create("http://example.com"),root,true,root.resolve("missing").toString(),"");
        assertThrows(IllegalArgumentException.class,settings::validateTarget);
        assertEquals("OBSERVER_UNAVAILABLE",settings.observerProblem());
        assertThrows(IllegalArgumentException.class,() -> new LoaderSettings(java.net.URI.create("http://127.0.0.1"),root,false,"","").validateTarget());
    }
    @Test void expiredObserverHeartbeatStopsAnActiveRun() throws Exception {
        String id=start(Map.of("rate",10,"seconds",1));
        Files.writeString(Path.of(stop+".heartbeat"),Instant.now().minusSeconds(30).toString());
        JsonNode result=await(id);assertEquals("STOPPED",result.path("state").asText());
        assertEquals("OBSERVER_STALE",result.path("stopReason").asText());
        assertTrue(result.path("report").path("attempts").asInt()<10);
    }
    @Test void retryAfterHttpDateIsADeadlineFloor() {
        Instant now=Instant.parse("2026-10-06T12:00:00Z");
        assertEquals(2000,LoadRunService.retryDelay(RunSpec.Policy.IMMEDIATE,1,"Tue, 6 Oct 2026 12:00:02 GMT",new Random(9),now));
    }

    private String start(Map<String,Object> request) throws Exception {
        ResponseEntity<String> response=api.postForEntity("/load/runs",request,String.class);
        assertEquals(202,response.getStatusCode().value(),response.getBody());return json.readTree(response.getBody()).path("runId").asText();
    }
    private JsonNode await(String id) throws Exception {
        long until=System.nanoTime()+8_000_000_000L;
        while (System.nanoTime()<until) {
            JsonNode result=json.readTree(api.getForObject("/load/runs/"+id,String.class));
            if (Set.of("COMPLETED","FAILED","STOPPED").contains(result.path("state").asText()) && result.has("report")) {
                // active is cleared only after the final report has been flushed.
                Thread.sleep(30);return result;
            }
            Thread.sleep(20);
        }
        fail("run did not finish");return null;
    }
    static void handle(HttpExchange exchange) {
        try {
            String path=exchange.getRequestURI().getPath();
            if (path.contains("readiness")) { respond(exchange,200,Map.of("status","UP"));return; }
            if (path.equals("/api/demo/events")) { fixtures.incrementAndGet();respond(exchange,201,Map.of("id",UUID.randomUUID().toString()));return; }
            if (path.endsWith("/seats")) { respond(exchange,200,List.of(Map.of("seatNumber",1,"availability","AVAILABLE")));return; }
            if (!path.equals("/api/holds")) { respond(exchange,404,Map.of());return; }
            int number=posts.incrementAndGet();String key=exchange.getRequestHeaders().getFirst("Idempotency-Key");
            String raw=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            keys.add(key);payloads.add(raw);
            if (conflict) { respond(exchange,409,Map.of("code","SEAT_UNAVAILABLE"));return; }
            if (retryBusy) { exchange.getResponseHeaders().add("Retry-After","1");respond(exchange,503,Map.of("code","DATABASE_UNAVAILABLE"));return; }
            boolean replay=bookings.containsKey(key);JsonNode payload=json.readTree(raw);
            Map<String,Object> result=new LinkedHashMap<>(bookings.computeIfAbsent(key,k -> new LinkedHashMap<>(Map.of(
                    "id",UUID.randomUUID().toString(),"eventId",payload.path("eventId").asText(),"seatNumber",payload.path("seatNumber").asInt(),
                    "buyerId",payload.path("buyerId").asText(),"createdAt",Instant.now().toString(),"expiresAt",Instant.now().plusSeconds(120).toString()))));
            result.put("replayed",replay);
            if (loseFirst && number==1) { exchange.close();return; }
            if (delay>0) Thread.sleep(delay);
            if (slowBody) {
                byte[] bytes=json.writeValueAsBytes(result);exchange.sendResponseHeaders(201,bytes.length);
                exchange.getResponseBody().write(bytes,0,1);exchange.getResponseBody().flush();Thread.sleep(500);
                exchange.getResponseBody().write(bytes,1,bytes.length-1);exchange.close();return;
            }
            respond(exchange,replay ? 200 : 201,result);
        } catch (Exception e) { exchange.close(); }
    }
    private static void respond(HttpExchange exchange,int status,Object body) throws Exception {
        byte[] bytes=json.writeValueAsBytes(body);exchange.getResponseHeaders().add("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
}

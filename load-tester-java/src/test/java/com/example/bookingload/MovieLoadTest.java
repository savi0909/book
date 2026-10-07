package com.example.bookingload;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class MovieLoadTest {
    static final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    static final Path root=Path.of("target","movie-tests-"+UUID.randomUUID());
    static final Path stop=root.resolve("STOP");
    static final ExecutorService serverThreads=Executors.newVirtualThreadPerTaskExecutor();
    static final LocalDate tomorrow=LocalDate.now(MovieChoices.IST).plusDays(1);
    static final String hot="aaaaaaaa-0000-0000-0000-000000000001",plain="aaaaaaaa-0000-0000-0000-000000000002";
    /** Seat owner per show/seat: the stub enforces exclusive ownership like the real API. */
    static final Map<String,String> owners=new ConcurrentHashMap<>();
    static final Map<String,Map<String,Object>> bookings=new ConcurrentHashMap<>();
    static final Set<String> holdKeys=ConcurrentHashMap.newKeySet();
    static HttpServer stub;
    static {
        try {
            Files.createDirectories(root);
            stub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            stub.setExecutor(serverThreads);stub.createContext("/",MovieLoadTest::handle);stub.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("loadtest.target",() -> "http://127.0.0.1:"+stub.getAddress().getPort());
        r.add("loadtest.results-dir",() -> root.resolve("results").toString());
        r.add("loadtest.allow-local",() -> "true");r.add("loadtest.observer-stop-file",stop::toString);
    }
    @Autowired TestRestTemplate api;
    @BeforeEach void reset() throws Exception {
        owners.clear();bookings.clear();holdKeys.clear();
        Files.writeString(Path.of(stop+".heartbeat"),Instant.now().toString());
    }
    @AfterAll static void shutdown() { stub.stop(0);serverThreads.shutdownNow(); }

    @Test void bookingJourneysConfirmWithoutDoubleSellingAndReportRevenue() throws Exception {
        JsonNode result=await(start(Map.of("rate",20,"seconds",1,"bookPercent",100,"cancelPercent",0,
                "abandonPercent",0,"browsePercent",0,"hotPercent",100)));
        assertEquals("COMPLETED",result.path("state").asText());
        JsonNode report=result.path("report");
        assertEquals(20,report.path("started").asInt());
        int confirmed=report.path("outcomes").path("CONFIRMED").asInt();
        assertTrue(confirmed>0);
        long soldSeats=bookings.values().stream().filter(b -> b.get("state").equals("CONFIRMED"))
                .mapToLong(b -> ((List<?>)b.get("seats")).size()).sum();
        assertEquals(soldSeats,report.path("confirmedSeats").asLong());
        assertEquals(soldSeats*10000,report.path("confirmedRevenueMinor").asLong());
        assertEquals(owners.size(),soldSeats,"every owned seat belongs to exactly one confirmed booking");
        // Hot demand concentrates on the evening blockbuster before spilling to the other show.
        assertEquals(hot,report.path("hottestShows").get(0).path("showId").asText());
        assertEquals(confirmed,report.path("steps").path("CHECKOUT").path("calls").asInt());
        // Conflict retries use a new key per hold, never the same key twice.
        assertEquals(report.path("steps").path("HOLD").path("calls").asInt(),holdKeys.size());
    }
    @Test void cancelAbandonAndBrowseFollowTheMix() throws Exception {
        JsonNode cancelled=await(start(Map.of("rate",4,"seconds",1,"bookPercent",0,"cancelPercent",100,"abandonPercent",0,"browsePercent",0)));
        // Concurrent groups of up to 10 compete for 12 seats per show, so some legitimately sell out.
        JsonNode outcomes=cancelled.path("report").path("outcomes");
        assertTrue(outcomes.path("CANCELLED").asInt()>=1);
        assertEquals(4,outcomes.path("CANCELLED").asInt()+outcomes.path("SOLD_OUT").asInt()+outcomes.path("SEAT_CONFLICT").asInt());
        assertEquals(0,outcomes.path("CONFIRMED").asInt());
        assertTrue(owners.isEmpty(),"cancel releases every seat");
        JsonNode abandoned=await(start(Map.of("rate",3,"seconds",1,"bookPercent",0,"cancelPercent",0,"abandonPercent",100,"browsePercent",0)));
        JsonNode left=abandoned.path("report").path("outcomes");
        assertTrue(left.path("ABANDONED").asInt()>=1);
        assertEquals(3,left.path("ABANDONED").asInt()+left.path("SOLD_OUT").asInt()+left.path("SEAT_CONFLICT").asInt());
        assertFalse(abandoned.path("report").path("steps").has("CHECKOUT"));
        JsonNode browsed=await(start(Map.of("rate",3,"seconds",1,"bookPercent",0,"cancelPercent",0,"abandonPercent",0,"browsePercent",100)));
        assertEquals(3,browsed.path("report").path("outcomes").path("BROWSED").asInt());
        assertFalse(browsed.path("report").path("steps").has("HOLD"));
    }
    @Test void invalidSpecsAndMissingObserverAreRejectedBeforeAnyCall() throws Exception {
        assertEquals(400,post("/load/movie-runs",Map.of("bookPercent",50)).getStatusCode().value());
        assertEquals(400,post("/load/movie-runs",Map.of("rate",21)).getStatusCode().value());
        assertEquals(400,post("/load/movie-runs",Map.of("rate",20,"seconds",601)).getStatusCode().value());
        assertEquals(400,post("/load/movie-runs",Map.of("date",tomorrow.plusDays(3).toString())).getStatusCode().value());
        Files.delete(Path.of(stop+".heartbeat"));
        assertEquals(409,post("/load/movie-runs",Map.of("rate",1,"seconds",1)).getStatusCode().value());
    }
    @Test void movieAndGenericRunsShareOneSlot() throws Exception {
        String id=start(Map.of("rate",2,"seconds",2,"browsePercent",100,"bookPercent",0,"cancelPercent",0,"abandonPercent",0));
        assertEquals(409,post("/load/runs",Map.of("rate",1,"seconds",1)).getStatusCode().value());
        assertEquals(409,post("/load/movie-runs",Map.of("rate",1,"seconds",1)).getStatusCode().value());
        assertEquals("COMPLETED",await(id).path("state").asText());
    }
    @Test void seatChoiceKeepsGroupsAdjacentInsideOneClass() throws Exception {
        JsonNode map=json.readTree("""
            [{"seatNumber":1,"category":"A","availability":"AVAILABLE"},{"seatNumber":2,"category":"A","availability":"BOOKED"},
             {"seatNumber":3,"category":"A","availability":"AVAILABLE"},{"seatNumber":4,"category":"B","availability":"AVAILABLE"},
             {"seatNumber":5,"category":"B","availability":"AVAILABLE"},{"seatNumber":6,"category":"B","availability":"AVAILABLE"}]""");
        for (int seed=0;seed<50;seed++) {
            assertEquals(List.of(4,5,6),MovieChoices.seats(map,3,new Random(seed)));
            List<Integer> pair=MovieChoices.seats(map,2,new Random(seed));
            assertTrue(pair.equals(List.of(4,5)) || pair.equals(List.of(5,6)) || pair.equals(List.of(1,3)),pair.toString());
        }
        assertNull(MovieChoices.seats(map,4,new Random(1)));
        Random random=new Random(7);int[] sizes=new int[11];
        for (int i=0;i<10000;i++) sizes[MovieChoices.groupSize(random)]++;
        assertTrue(sizes[2]>sizes[1] && sizes[2]>sizes[5] && sizes[10]>0);
    }

    static ResponseEntity<String> post(TestRestTemplate api,String path,Object body) {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        return api.postForEntity(path,new HttpEntity<>(body,headers),String.class);
    }
    ResponseEntity<String> post(String path,Object body) { return post(api,path,body); }
    String start(Map<String,Object> body) throws Exception {
        ResponseEntity<String> response=post("/load/movie-runs",body);
        assertEquals(202,response.getStatusCode().value(),response.getBody());
        return json.readTree(response.getBody()).path("runId").asText();
    }
    JsonNode await(String id) throws Exception {
        for (int i=0;i<200;i++) {
            JsonNode status=json.readTree(api.getForObject("/load/movie-runs/"+id,String.class));
            if (!Set.of("PREPARING","RUNNING").contains(status.path("state").asText())) return status;
            Thread.sleep(100);
        }
        throw new AssertionError("run did not finish");
    }

    static String show(String id,String title,int hour) {
        Instant start=tomorrow.atTime(hour,0).atZone(MovieChoices.IST).toInstant();
        return "{\"id\":\""+id+"\",\"title\":\""+title+"\",\"startsAt\":\""+start+"\"}";
    }
    static void handle(HttpExchange exchange) {
        try (exchange) {
            String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
            String body=new String(exchange.getRequestBody().readAllBytes());
            if (path.equals("/actuator/health/readiness")) { send(exchange,200,"{\"status\":\"UP\"}");return; }
            if (path.equals("/api/movie/now-showing")) {
                send(exchange,200,"[{\"title\":\"Big Film\",\"blockbuster\":true},{\"title\":\"Small Film\",\"blockbuster\":false}]");return;
            }
            if (path.equals("/api/movie/multiplexes")) {
                send(exchange,200,"[{\"id\":\"m1\",\"name\":\"PVR Test\",\"city\":\"Pune\"}]");return;
            }
            if (path.equals("/api/movie/shows")) {
                send(exchange,200,"["+show(hot,"Big Film",19)+","+show(plain,"Small Film",10)+"]");return;
            }
            if (path.startsWith("/api/movie/shows/") && path.endsWith("/seats")) {
                String showId=path.split("/")[4];StringBuilder seats=new StringBuilder("[");
                for (int n=1;n<=12;n++) seats.append(n>1 ? "," : "").append("{\"seatNumber\":").append(n)
                        .append(",\"category\":\"").append(n<=4 ? "A" : "B").append("\",\"availability\":\"")
                        .append(owners.containsKey(showId+"/"+n) ? "BOOKED" : "AVAILABLE").append("\"}");
                send(exchange,200,seats.append("]").toString());return;
            }
            if (path.equals("/api/movie/holds") && method.equals("POST")) {
                holdKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                JsonNode request=json.readTree(body);String showId=request.path("showId").asText();
                List<Integer> seats=new ArrayList<>();request.path("seatNumbers").forEach(n -> seats.add(n.asInt()));
                String id=UUID.randomUUID().toString();
                synchronized (owners) {
                    if (seats.stream().anyMatch(n -> owners.containsKey(showId+"/"+n))) {
                        send(exchange,409,"{\"code\":\"SEAT_UNAVAILABLE\",\"message\":\"taken\"}");return;
                    }
                    seats.forEach(n -> owners.put(showId+"/"+n,id));
                }
                bookings.put(id,new ConcurrentHashMap<>(Map.of("id",id,"showId",showId,"state","HELD","seats",seats)));
                send(exchange,201,"{\"id\":\""+id+"\",\"state\":\"HELD\"}");return;
            }
            if (path.startsWith("/api/movie/bookings/")) {
                String[] parts=path.split("/");Map<String,Object> booking=bookings.get(parts[4]);
                if (booking==null) { send(exchange,404,"{\"code\":\"NOT_FOUND\"}");return; }
                int seats=((List<?>)booking.get("seats")).size();
                if (parts.length==6 && parts[5].equals("checkout")) {
                    booking.put("state","PAYMENT_PENDING");
                    send(exchange,201,"{\"booking\":{\"state\":\"PAYMENT_PENDING\"},\"payment\":{\"state\":\"PENDING\"},\"replayed\":false}");return;
                }
                if (parts.length==6 && parts[5].equals("cancel")) {
                    booking.put("state","CANCELLED");
                    synchronized (owners) { owners.values().removeIf(owner -> owner.equals(parts[4])); }
                    send(exchange,200,"{\"state\":\"CANCELLED\"}");return;
                }
                if (booking.get("state").equals("PAYMENT_PENDING")) booking.put("state","CONFIRMED");
                send(exchange,200,"{\"state\":\""+booking.get("state")+"\",\"amountMinor\":"+seats*10000+"}");return;
            }
            send(exchange,404,"{\"code\":\"NOT_FOUND\"}");
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static void send(HttpExchange exchange,int status,String body) throws java.io.IOException {
        byte[] bytes=body.getBytes();exchange.getResponseHeaders().add("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
}

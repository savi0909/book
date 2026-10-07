package com.example.bookingload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import static com.example.bookingload.MovieChoices.Kind;

/**
 * Open-model movie advance-booking simulation. Arrivals follow a fixed schedule; each
 * journey runs sequentially on its own virtual thread (one HTTP call at a time), while a
 * shared semaphore bounds concurrent HTTP calls to the gateway. Timeouts are recorded as
 * UNKNOWN and never retried: a hold/checkout may have committed. A seat conflict starts a
 * second, separately keyed hold for different seats; each key's 409 stays terminal.
 */
@Service
public class MovieRunService {
    static final int MAX_LIVE_JOURNEYS=400,POLL_MS=300;
    record Call(int journey,String step,int status,double latencyMs,long second) {}
    static final class Journey {
        final int index,groupSize;
        final Kind kind;
        final boolean hot;
        final Map<String,Object> multiplex;
        final String buyer;
        volatile String outcome="NOT_STARTED",showId,title,bookingId,code;
        volatile int seats;
        volatile long amountMinor;
        volatile double ms;
        Journey(String run,int index,Kind kind,int groupSize,boolean hot,Map<String,Object> multiplex) {
            this.index=index;this.kind=kind;this.groupSize=groupSize;this.hot=hot;this.multiplex=multiplex;
            buyer="mv-"+run+"-"+index;
        }
    }
    static final class Run {
        final String id="mv"+UUID.randomUUID().toString().replace("-","").substring(0,14);
        final Instant startedAt=Instant.now();
        final MovieSpec spec;
        final Path dir;
        final List<Journey> journeys=new CopyOnWriteArrayList<>();
        final Queue<Call> calls=new ConcurrentLinkedQueue<>();
        final AtomicInteger live=new AtomicInteger(),peak=new AtomicInteger(),transientStreak=new AtomicInteger();
        final AtomicBoolean stopped=new AtomicBoolean();
        final Set<String> blockbusters=ConcurrentHashMap.newKeySet();
        volatile String state="PREPARING",stopReason,error;
        volatile long measuredStart;
        volatile Map<String,Object> report;
        Semaphore http;
        Run(MovieSpec spec,Path base) { this.spec=spec;this.dir=base.resolve(id); }
    }
    private final LoaderSettings settings;
    private final ObjectMapper mapper;
    private final BookingClient client;
    private final RunSlot slot;
    private final ExecutorService threads=Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService watcher=Executors.newSingleThreadScheduledExecutor();
    private final Map<String,Run> runs=new LinkedHashMap<>();
    private volatile Run active;

    MovieRunService(LoaderSettings settings,ObjectMapper mapper,BookingClient client,RunSlot slot) {
        this.settings=settings;this.mapper=mapper;this.client=client;this.slot=slot;
    }

    public synchronized String start(MovieSpec input) throws IOException {
        if (runs.size()>=20) throw new IllegalStateException("Process history limit reached (20 movie runs); reports remain on disk");
        settings.validateTarget();
        MovieSpec spec=(input==null ? MovieSpec.defaults() : input).normalized(settings.allowLocal(),LocalDate.now(MovieChoices.IST));
        String problem=settings.observerProblem();
        if (problem!=null) throw new IllegalStateException(problem);
        if (client.openCalls()!=0) throw new IllegalStateException("Previous HTTP task is still closing; no new run admitted");
        Run run=new Run(spec,settings.resultsDir());
        if (!slot.acquire(run.id)) throw new IllegalStateException("Run "+slot.owner()+" is still active");
        try {
            Files.createDirectories(settings.resultsDir());Files.createDirectory(run.dir);
            write(run,"manifest.json",Map.of("runId",run.id,"request",spec,"startedAt",run.startedAt.toString(),
                    "target",settings.target().toString(),"localValidation",settings.allowLocal(),"contract","movie-advance-booking-v1"));
        } catch (IOException | RuntimeException e) { slot.release(run.id);throw e; }
        runs.put(run.id,run);active=run;
        threads.submit(() -> execute(run));
        return run.id;
    }

    private void execute(Run run) {
        ScheduledFuture<?> monitor=null;
        List<Future<?>> tasks=new ArrayList<>();
        try {
            monitor=watcher.scheduleAtFixedRate(() -> watch(run),0,250,TimeUnit.MILLISECONDS);
            BookingClient.Reply ready=client.call(HttpMethod.GET,"/actuator/health/readiness",null,null,2000);
            if (ready.status()!=200 || !ready.body().path("status").asText().equals("UP"))
                throw new IllegalStateException("Gateway is not ready");
            List<Map<String,Object>> multiplexes=catalog(run);
            Random random=new Random(run.spec.seed());
            for (int i=0;i<run.spec.total();i++)
                run.journeys.add(new Journey(run.id,i,MovieChoices.kind(run.spec,random),MovieChoices.groupSize(random),
                        random.nextInt(100)<run.spec.hotPercent(),multiplexes.get(random.nextInt(multiplexes.size()))));
            write(run,"plan.json",run.journeys.stream().map(j -> Map.of("journey",j.index,"kind",j.kind,"groupSize",j.groupSize,
                    "hot",j.hot,"multiplexId",j.multiplex.get("id"),"buyer",j.buyer)).toList());
            run.http=new Semaphore(run.spec.concurrency());
            run.measuredStart=System.nanoTime();run.state="RUNNING";
            long interval=1_000_000_000L/run.spec.rate();
            for (Journey journey:run.journeys) {
                long due=run.measuredStart+journey.index*interval;
                sleepUntil(due,run);
                if (run.stopped.get()) break;
                if (System.nanoTime()-due>500_000_000L) { stop(run,"SCHEDULER_LAG");break; }
                if (run.live.get()>=MAX_LIVE_JOURNEYS) { stop(run,"GENERATOR_SATURATED");break; }
                journey.outcome="IN_FLIGHT";
                run.peak.accumulateAndGet(run.live.incrementAndGet(),Math::max);
                tasks.add(threads.submit(() -> {
                    long started=System.nanoTime();
                    try { journey.outcome=journey(run,journey); }
                    catch (InterruptedException e) { journey.outcome="STOPPED";Thread.currentThread().interrupt(); }
                    catch (Exception e) { journey.outcome="CLIENT_FAILURE";stop(run,"CLIENT_FAILURE"); }
                    finally { journey.ms=(System.nanoTime()-started)/1e6;run.live.decrementAndGet(); }
                }));
            }
        } catch (Exception e) { run.error=e.getClass().getSimpleName()+": "+e.getMessage();stop(run,"RUN_FAILURE"); }
        finally {
            for (Future<?> task:tasks) try { task.get(); } catch (Exception ignored) { }
            if (monitor!=null) monitor.cancel(false);
            synchronized(this) {
                run.state=run.error!=null ? "FAILED" : run.stopped.get() ? "STOPPED" : "COMPLETED";
                try { finish(run); } catch (IOException e) { run.state="FAILED";run.error="REPORT_WRITE_FAILED"; }
                if (active==run) active=null;
                slot.release(run.id);
            }
        }
    }

    /** Catalog discovery is read-only, so a failure here simply fails setup. */
    private List<Map<String,Object>> catalog(Run run) throws InterruptedException {
        BookingClient.Reply slate=client.call(HttpMethod.GET,"/api/movie/now-showing",null,null,3500);
        if (slate.status()!=200) throw new IllegalStateException("Now-showing slate unavailable: "+slate.status());
        for (JsonNode movie:slate.body()) if (movie.path("blockbuster").asBoolean()) run.blockbusters.add(movie.path("title").asText());
        List<Map<String,Object>> result=new ArrayList<>();
        List<String> cities=run.spec.cities().isEmpty() ? Collections.singletonList(null) : run.spec.cities();
        for (String city:cities) {
            String path="/api/movie/multiplexes"+(city==null ? "" : "?city="+URLEncoder.encode(city,StandardCharsets.UTF_8));
            BookingClient.Reply reply=client.call(HttpMethod.GET,path,null,null,3500);
            if (reply.status()!=200) throw new IllegalStateException("Catalog unavailable: "+reply.status());
            for (JsonNode m:reply.body()) result.add(Map.of("id",m.path("id").asText(),"name",m.path("name").asText(),"city",m.path("city").asText()));
        }
        if (result.isEmpty()) throw new IllegalStateException("No catalog multiplexes for the requested cities");
        return result;
    }

    private String journey(Run run,Journey j) throws InterruptedException {
        Random random=new Random(run.spec.seed()^(31L*j.index+7));
        BookingClient.Reply shows=call(run,j,"SHOWS",HttpMethod.GET,"/api/movie/shows?multiplexId="+j.multiplex.get("id")
                +"&date="+run.spec.date()+"&limit=100",null,null);
        if (shows==null) return stoppedAt("SHOWS");
        if (shows.status()!=200) return failure("SHOWS",shows);
        JsonNode show=MovieChoices.show(shows.body(),run.blockbusters,j.hot,Instant.now(),random);
        if (show==null) return "NO_OPEN_SHOWS";
        j.showId=show.path("id").asText();j.title=show.path("title").asText();
        for (int attempt=1;attempt<=2;attempt++) {
            BookingClient.Reply map=call(run,j,"SEATS",HttpMethod.GET,"/api/movie/shows/"+j.showId+"/seats?limit=500",null,null);
            if (map==null) return stoppedAt("SEATS");
            if (map.status()!=200) return failure("SEATS",map);
            if (j.kind==Kind.BROWSE) return "BROWSED";
            List<Integer> seats=MovieChoices.seats(map.body(),j.groupSize,random);
            if (seats==null) return "SOLD_OUT";
            String key="mv:"+run.id+":"+j.index+":h"+attempt;
            BookingClient.Reply hold=call(run,j,"HOLD",HttpMethod.POST,"/api/movie/holds",Map.of("showId",j.showId,
                    "seatNumbers",seats,"buyerId",j.buyer,"ttlSeconds",run.spec.ttlSeconds()),key);
            if (hold==null) return stoppedAt("HOLD");
            if (hold.status()==201) { j.bookingId=hold.body().path("id").asText();j.seats=seats.size();break; }
            if (ambiguous(hold)) return "UNKNOWN_HOLD";
            j.code=hold.body().path("code").asText();
            if (hold.status()!=409) return "HOLD_REJECTED";
            if (!j.code.equals("SEAT_UNAVAILABLE")) return "HOLD_CONFLICT";
            if (attempt==2) return "SEAT_CONFLICT";
        }
        if (j.kind==Kind.ABANDON) return "ABANDONED";
        BookingClient.Reply checkout=call(run,j,"CHECKOUT",HttpMethod.POST,"/api/movie/bookings/"+j.bookingId+"/checkout",
                Map.of(),"mv:"+run.id+":"+j.index+":pay");
        if (checkout==null) return stoppedAt("CHECKOUT");
        if (ambiguous(checkout)) return "UNKNOWN_PAYMENT";
        if (checkout.status()!=201) { j.code=checkout.body().path("code").asText();return "CHECKOUT_REJECTED"; }
        long deadline=System.nanoTime()+run.spec.paymentWaitMs()*1_000_000L;
        JsonNode booking=checkout.body().path("booking");
        while (booking.path("state").asText().equals("PAYMENT_PENDING")) {
            if (System.nanoTime()>deadline) return "PAYMENT_PENDING_TIMEOUT";
            sleepUntil(System.nanoTime()+POLL_MS*1_000_000L,run);
            BookingClient.Reply poll=call(run,j,"POLL",HttpMethod.GET,"/api/movie/bookings/"+j.bookingId,null,null);
            if (poll==null) return stoppedAt("POLL");
            if (poll.status()==200) booking=poll.body();
            else if (!ambiguous(poll)) return failure("POLL",poll);
        }
        switch (booking.path("state").asText()) {
            case "CONFIRMED" -> j.amountMinor=booking.path("amountMinor").asLong();
            case "HELD" -> { return "PAYMENT_FAILED"; }
            case "EXPIRED" -> { return "EXPIRED_DURING_PAYMENT"; }
            default -> { return "UNEXPECTED_"+booking.path("state").asText(); }
        }
        if (j.kind!=Kind.CANCEL) return "CONFIRMED";
        BookingClient.Reply cancel=call(run,j,"CANCEL",HttpMethod.POST,"/api/movie/bookings/"+j.bookingId+"/cancel",null,null);
        if (cancel==null) return "CONFIRMED_CANCEL_STOPPED";
        if (ambiguous(cancel)) return "UNKNOWN_CANCEL";
        return cancel.status()==200 && cancel.body().path("state").asText().equals("CANCELLED") ? "CANCELLED" : "CANCEL_REJECTED";
    }

    /** Returns null without calling when the run is stopping; otherwise one bounded HTTP call. */
    private BookingClient.Reply call(Run run,Journey j,String step,HttpMethod method,String path,Map<String,Object> body,String key)
            throws InterruptedException {
        if (run.stopped.get()) return null;
        if (!run.http.tryAcquire(2,TimeUnit.SECONDS)) { stop(run,"GENERATOR_SATURATED");return null; }
        try {
            if (run.stopped.get()) return null;
            BookingClient.Reply reply=client.call(method,path,body,key,run.spec.timeoutMs());
            run.calls.add(new Call(j.index,step,reply.status(),reply.latencyMs(),(System.nanoTime()-run.measuredStart)/1_000_000_000L));
            if (reply.status()==0 || reply.status()>=500) {
                if (run.transientStreak.incrementAndGet()>=20) stop(run,"TRANSIENT_STREAK");
            } else run.transientStreak.set(0);
            if (!reply.closed()) stop(run,"HTTP_TASK_NOT_CLOSED");
            return reply;
        } finally { run.http.release(); }
    }
    private static boolean ambiguous(BookingClient.Reply reply) { return reply.status()==0 || reply.status()==408 || reply.status()>=500; }
    private static String failure(String step,BookingClient.Reply reply) { return (ambiguous(reply) ? "UNKNOWN_" : "FAILED_")+step; }
    private static String stoppedAt(String step) { return "STOPPED_AT_"+step; }

    private static void sleepUntil(long due,Run run) throws InterruptedException {
        while (!run.stopped.get()) {
            long remaining=due-System.nanoTime();if (remaining<=0) return;
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining,50_000_000L));
        }
    }
    private void watch(Run run) {
        String problem=settings.observerProblem();
        if (problem!=null) stop(run,problem);
        long limit=(run.spec.seconds()+120)*1000L;
        if (Duration.between(run.startedAt,Instant.now()).toMillis()>limit) stop(run,"RUN_DEADLINE");
        Runtime runtime=Runtime.getRuntime();
        if (runtime.totalMemory()-runtime.freeMemory()>runtime.maxMemory()*0.85) stop(run,"GENERATOR_HEAP");
    }
    private static void stop(Run run,String reason) { if (run.stopped.compareAndSet(false,true)) run.stopReason=reason; }
    public synchronized Map<String,Object> stop(String id) {
        Run run=required(id);if (active==run) stop(run,"USER_STOP");return status(id);
    }

    public synchronized List<String> list() { return runs.values().stream().map(r -> r.id+" "+r.state).toList(); }
    public synchronized Map<String,Object> status(String id) {
        Run run=required(id);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("runId",id);result.put("state",run.state);result.put("request",run.spec);result.put("startedAt",run.startedAt.toString());
        result.put("liveJourneys",run.live.get());result.put("callsSoFar",run.calls.size());
        result.put("startedJourneys",run.journeys.stream().filter(j -> !j.outcome.equals("NOT_STARTED")).count());
        if (run.stopReason!=null) result.put("stopReason",run.stopReason);
        if (run.error!=null) result.put("error",run.error);
        if (run.report!=null) result.put("report",run.report);
        return result;
    }
    private Run required(String id) {
        Run run=runs.get(id);if (run==null) throw new NoSuchElementException("Unknown run");return run;
    }

    private void finish(Run run) throws IOException {
        List<Journey> started=run.journeys.stream().filter(j -> !j.outcome.equals("NOT_STARTED")).toList();
        List<Call> calls=List.copyOf(run.calls);
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("scheduled",run.spec.total());report.put("started",started.size());report.put("date",run.spec.date().toString());
        report.put("maxLiveJourneys",run.peak.get());report.put("httpCalls",calls.size());
        report.put("outcomes",LoadRunService.counts(started.stream().map(j -> j.outcome).toList()));
        report.put("kinds",LoadRunService.counts(started.stream().map(j -> j.kind.name()).toList()));
        List<Journey> confirmed=started.stream().filter(j -> j.outcome.equals("CONFIRMED")).toList();
        report.put("confirmedBookings",confirmed.size());
        report.put("confirmedSeats",confirmed.stream().mapToInt(j -> j.seats).sum());
        report.put("confirmedRevenueMinor",confirmed.stream().mapToLong(j -> j.amountMinor).sum());
        report.put("cancelledSeats",started.stream().filter(j -> j.outcome.equals("CANCELLED")).mapToInt(j -> j.seats).sum());
        report.put("journeyLatencyMs",LoadRunService.latency(started.stream().mapToDouble(j -> j.ms).toArray()));
        Map<String,Object> steps=new TreeMap<>();
        for (String step:List.of("SHOWS","SEATS","HOLD","CHECKOUT","POLL","CANCEL")) {
            List<Call> group=calls.stream().filter(c -> c.step.equals(step)).toList();
            if (!group.isEmpty()) steps.put(step,Map.of("calls",group.size(),
                    "status",LoadRunService.counts(group.stream().map(c -> Integer.toString(c.status)).toList()),
                    "latencyMs",LoadRunService.latency(group.stream().mapToDouble(Call::latencyMs).toArray())));
        }
        report.put("steps",steps);
        Map<Long,Long> perSecond=new TreeMap<>();
        for (Call c:calls) perSecond.merge(c.second,1L,Long::sum);
        report.put("callsPerSecond",perSecond);
        Map<String,int[]> byShow=new HashMap<>();Map<String,String> titles=new HashMap<>();
        for (Journey j:confirmed) { byShow.computeIfAbsent(j.showId,k -> new int[1])[0]+=j.seats;titles.put(j.showId,j.title); }
        report.put("hottestShows",byShow.entrySet().stream().sorted((a,b) -> b.getValue()[0]-a.getValue()[0]).limit(10)
                .map(e -> Map.of("showId",e.getKey(),"title",titles.get(e.getKey()),"confirmedSeats",e.getValue()[0])).toList());
        write(run,"journeys.json",started.stream().map(j -> {
            Map<String,Object> v=new LinkedHashMap<>();
            v.put("journey",j.index);v.put("kind",j.kind);v.put("outcome",j.outcome);v.put("groupSize",j.groupSize);
            v.put("hot",j.hot);v.put("city",j.multiplex.get("city"));v.put("multiplexId",j.multiplex.get("id"));
            if (j.showId!=null) v.put("showId",j.showId);
            if (j.bookingId!=null) v.put("bookingId",j.bookingId);
            if (j.code!=null) v.put("code",j.code);
            v.put("ms",j.ms);return v;
        }).toList());
        run.report=report;write(run,"report.json",status(run.id));
    }
    private void write(Run run,String file,Object value) throws IOException {
        Files.writeString(run.dir.resolve(file),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
    @PreDestroy void close() {
        Run run=active;if (run!=null) stop(run,"SHUTDOWN");watcher.shutdownNow();threads.shutdownNow();
    }
}

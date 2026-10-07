package com.example.bookingload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import static com.example.bookingload.RunSpec.*;

@Service
public class LoadRunService {
    record Attempt(int operation,String kind,int number,int status,String phase,double latencyMs,long startedSecond,boolean virtualThread) {}
    static final class Operation {
        final int index,seat;
        final boolean hold;
        final String buyer,key;
        final Map<String,Object> payload;
        volatile String outcome="NOT_STARTED",bookingId;
        volatile int attempts;
        volatile double logicalMs,discoveryMs;
        Operation(String id,UUID eventId,int index,int seat,boolean hold,int ttl) {
            this.index=index;this.seat=seat;this.hold=hold;
            buyer="java-"+id+"-"+index;key="hold:"+id+":java:"+index;
            payload=Map.of("eventId",eventId,"seatNumber",seat,"buyerId",buyer,"ttlSeconds",ttl);
        }
    }
    static final class Run {
        final String id=UUID.randomUUID().toString().replace("-","").substring(0,16);
        final Instant startedAt=Instant.now();
        final RunSpec spec;
        final Path dir;
        final List<Operation> operations=new CopyOnWriteArrayList<>();
        final Queue<Attempt> attempts=new ConcurrentLinkedQueue<>();
        final List<Future<?>> tasks=new CopyOnWriteArrayList<>();
        final AtomicInteger inFlight=new AtomicInteger(),peak=new AtomicInteger(),transientStreak=new AtomicInteger();
        final AtomicBoolean stopped=new AtomicBoolean();
        volatile String state="PREPARING",stopReason,error;
        volatile UUID eventId;
        volatile long measuredStart,measuredEnd;
        volatile Map<String,Object> report;
        boolean discovering;
        Run(RunSpec spec,Path base) { this.spec=spec;this.dir=base.resolve(id); }
    }
    private final LoaderSettings settings;
    private final ObjectMapper mapper;
    private final BookingClient client;
    private final RunSlot slot;
    private final ExecutorService threads=Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService watcher=Executors.newSingleThreadScheduledExecutor();
    private final Map<String,Run> runs=new LinkedHashMap<>();
    private volatile Run active;

    LoadRunService(LoaderSettings settings,ObjectMapper mapper,BookingClient client,RunSlot slot) {
        this.settings=settings;this.mapper=mapper;this.client=client;this.slot=slot;
    }
    public synchronized String start(RunSpec input) throws IOException {
        if (active!=null) throw new IllegalStateException("Run "+active.id+" is still active");
        if (client.openCalls()!=0) throw new IllegalStateException("Previous HTTP task is still closing; no new run admitted");
        if (runs.size()>=20) throw new IllegalStateException("Process history limit reached (20 runs); retained reports remain on disk");
        settings.validateTarget();
        RunSpec spec=(input==null ? RunSpec.defaults() : input).normalized(settings.allowLocal());
        String problem=settings.observerProblem();
        if (problem!=null) throw new IllegalStateException(problem);
        Run run=new Run(spec,settings.resultsDir());
        if (!slot.acquire(run.id)) throw new IllegalStateException("Run "+slot.owner()+" is still active");
        try {
            Files.createDirectories(settings.resultsDir());Files.createDirectory(run.dir);
            write(run,"manifest.json",Map.of("runId",run.id,"request",spec,"startedAt",run.startedAt.toString(),
                    "target",settings.target().toString(),"localValidation",settings.allowLocal(),"contract","generic-v1-holds"));
        } catch (IOException | RuntimeException e) { slot.release(run.id);throw e; }
        runs.put(run.id,run);active=run;
        threads.submit(() -> execute(run));
        return run.id;
    }

    private void execute(Run run) {
        ScheduledFuture<?> monitor=null;
        try {
            monitor=watcher.scheduleAtFixedRate(() -> watch(run),0,250,TimeUnit.MILLISECONDS);
            BookingClient.Reply ready=client.call(HttpMethod.GET,"/actuator/health/readiness",null,null,2000);
            if (ready.status()!=200 || !ready.body().path("status").asText().equals("UP"))
                throw new IllegalStateException("Gateway is not ready");
            if (run.stopped.get()) return;
            // Fixture creation is NOT idempotent: exactly one setup call, never automatic retry.
            BookingClient.Reply fixture=client.call(HttpMethod.POST,"/api/demo/events",
                    Map.of("name","Java load "+run.id,"seatCount",run.spec.seats()),null,3500);
            if (fixture.status()!=201) throw new IllegalStateException("Fixture creation failed or is uncertain; setup was not retried");
            run.eventId=UUID.fromString(fixture.body().path("id").asText());
            Random choices=new Random(run.spec.seed());
            for (int i=0;i<run.spec.total();i++) {
                boolean hold=run.spec.mode()==Mode.HOLD || (run.spec.mode()==Mode.MIXED && choices.nextInt(100)<run.spec.holdPercent());
                run.operations.add(new Operation(run.id,run.eventId,i,i%run.spec.seats()+1,hold,run.spec.ttlSeconds()));
            }
            write(run,"operations.json",run.operations.stream().map(op -> Map.of("index",op.index,"seat",op.seat,
                    "hold",op.hold,"buyer",op.buyer,"key",op.key,"eventId",run.eventId.toString())).toList());
            Semaphore capacity=new Semaphore(run.spec.concurrency());
            run.measuredStart=System.nanoTime();run.state="RUNNING";
            long interval=1_000_000_000L/run.spec.rate();
            for (Operation op:run.operations) {
                long due=run.measuredStart+op.index*interval;
                sleepUntil(due,run);
                if (run.stopped.get()) break;
                // No queue and no catch-up burst: stop instead of quietly lowering the offered rate.
                if (System.nanoTime()-due>100_000_000L) { stop(run,"SCHEDULER_LAG");break; }
                if (!capacity.tryAcquire()) { stop(run,"GENERATOR_SATURATED");break; }
                try { journal(run,Map.of("operation",op.index,"at",Instant.now().toString(),"phase","measured")); }
                catch (Exception e) { capacity.release();throw e; }
                op.outcome="IN_FLIGHT";
                int current=run.inFlight.incrementAndGet();run.peak.accumulateAndGet(current,Math::max);
                Future<?> task=threads.submit(() -> {
                    try { invoke(run,op,due+run.spec.deadlineMs()*1_000_000L,"measured",run.spec.maxAttempts()); }
                    finally { run.inFlight.decrementAndGet();capacity.release(); }
                });
                run.tasks.add(task);
            }
            awaitTasks(run);
        } catch (Exception e) { run.error=e.getClass().getSimpleName()+": "+e.getMessage();stop(run,"RUN_FAILURE"); }
        finally {
            // Keep watchdog active while draining. A run is never reported finished with live logical calls.
            try { awaitTasks(run); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (monitor!=null) monitor.cancel(false);
            // Publish terminal state, persisted report and release of the active slot together.
            synchronized(this) {
                run.state=run.error!=null ? "FAILED" : run.stopped.get() ? "STOPPED" : "COMPLETED";
                try { finish(run); } catch (IOException e) { run.state="FAILED";run.error="REPORT_WRITE_FAILED"; }
                if (active==run) active=null;
                slot.release(run.id);
            }
        }
    }

    private void invoke(Run run,Operation op,long deadline,String phase,int maxAttempts) {
        long logicalStart=System.nanoTime();
        boolean ambiguous=op.outcome.equals("UNKNOWN");
        Random random=new Random(run.spec.seed() ^ op.index);
        try {
            for (int attempt=1;attempt<=maxAttempts;attempt++) {
                long remaining=(deadline-System.nanoTime())/1_000_000L;
                if (remaining<100 || run.stopped.get()) break;
                if (phase.equals("discovery")) journal(run,Map.of("operation",op.index,"attempt",attempt,"phase",phase,"at",Instant.now().toString()));
                long second=run.measuredStart==0 ? 0 : (System.nanoTime()-run.measuredStart)/1_000_000_000L;
                op.attempts++;
                BookingClient.Reply reply;
                try { reply=client.call(op.hold ? HttpMethod.POST : HttpMethod.GET,
                        op.hold ? "/api/holds" : "/api/events/"+run.eventId+"/seats?limit=100",
                        op.hold ? op.payload : null,op.hold ? op.key : null,Math.min(remaining,run.spec.timeoutMs())); }
                catch (InterruptedException e) {
                    run.attempts.add(new Attempt(op.index,op.hold ? "HOLD" : "AVAILABILITY",attempt,0,phase,
                            (System.nanoTime()-logicalStart)/1e6,second,Thread.currentThread().isVirtual()));
                    ambiguous=op.hold;Thread.currentThread().interrupt();break;
                }
                run.attempts.add(new Attempt(op.index,op.hold ? "HOLD" : "AVAILABILITY",attempt,reply.status(),phase,
                        reply.latencyMs(),second,Thread.currentThread().isVirtual()));
                if (reply.status()==0 || reply.status()>=500) {
                    if (run.transientStreak.incrementAndGet()>=20) stop(run,"TRANSIENT_STREAK");
                } else run.transientStreak.set(0);
                if ((reply.status()==200 || reply.status()==201) && op.hold) {
                        if (validBooking(op,run.eventId,reply.body()) && reply.body().path("replayed").asBoolean()==(reply.status()==200)) {
                        op.bookingId=reply.body().path("id").asText();
                        op.outcome=reply.status()==201 ? "HELD" : "REPLAYED";return;
                    }
                    ambiguous=true;stop(run,"INVALID_BOOKING_RESPONSE");break;
                }
                if (!op.hold && reply.status()==200) { op.outcome="READ_OK";return; }
                if (reply.status()==0 || reply.status()==408 || reply.status()>=500) ambiguous|=op.hold;
                boolean transientReply=reply.status()==0 || reply.status()==408 || reply.status()==429
                        || reply.status()==502 || reply.status()==503 || reply.status()==504;
                if (!transientReply) {
                    op.outcome=ambiguous ? "UNKNOWN" : reply.status()==409 ? "CONFLICT" : "REJECTED";return;
                }
                if (!reply.closed()) { stop(run,"HTTP_TASK_NOT_CLOSED");break; }
                if (attempt==maxAttempts || run.spec.policy()==Policy.NONE && !phase.equals("discovery")) break;
                long delay=retryDelay(run.spec.policy(),attempt,reply.retryAfter(),random,Instant.now());
                if (delay==Long.MAX_VALUE || deadline-System.nanoTime()<(delay+100)*1_000_000L) break;
                sleepUntil(System.nanoTime()+delay*1_000_000L,run);
            }
            op.outcome=ambiguous ? "UNKNOWN" : run.stopped.get() ? "STOPPED" : "RETRY_EXHAUSTED";
        } catch (Exception e) { op.outcome=op.hold && op.attempts>0 ? "UNKNOWN" : "STOPPED";stop(run,"CLIENT_FAILURE"); }
        finally {
            if (phase.equals("measured")) op.logicalMs=(System.nanoTime()-logicalStart)/1e6;
            else op.discoveryMs=(System.nanoTime()-logicalStart)/1e6;
        }
    }

    static boolean validBooking(Operation op,UUID event,JsonNode body) {
        try {
            UUID.fromString(body.path("id").asText());
            Instant.parse(body.path("createdAt").asText());Instant.parse(body.path("expiresAt").asText());
            return body.path("eventId").asText().equals(event.toString()) && body.path("seatNumber").asInt()==op.seat
                    && body.path("buyerId").asText().equals(op.buyer);
        } catch (Exception e) { return false; }
    }
    static long retryDelay(Policy policy,int failedAttempt,String retryAfter,Random random,Instant now) {
        long floor=0;
        if (retryAfter!=null) {
            try {
                if (retryAfter.matches("[0-9]+")) floor=Math.multiplyExact(Long.parseLong(retryAfter),1000L);
                else floor=Math.max(0,Duration.between(now,ZonedDateTime.parse(retryAfter,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis());
            } catch (Exception e) { return Long.MAX_VALUE; } // Invalid floor: end the finite retry budget.
        }
        long jitter=policy==Policy.JITTER ? random.nextLong(Math.min(2000L,500L << Math.min(3,failedAttempt-1))+1) : 0;
        return Math.max(floor,jitter);
    }
    private static void sleepUntil(long due,Run run) throws InterruptedException {
        while (!run.stopped.get()) {
            long remaining=due-System.nanoTime();if (remaining<=0) return;
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining,50_000_000L));
        }
    }
    private static void awaitTasks(Run run) throws InterruptedException {
        for (Future<?> task:run.tasks) try { task.get(); } catch (CancellationException | ExecutionException ignored) { }
        // Future.cancel can finish before its worker's finally; wait for actual logical closure too.
        while (run.inFlight.get()>0) Thread.sleep(10);
    }
    private void watch(Run run) {
        String problem=settings.observerProblem();
        if (problem!=null) stop(run,problem);
        if (Duration.between(run.startedAt,Instant.now()).toMillis()>(settings.allowLocal() ? 30000 : 75000)) stop(run,"RUN_DEADLINE");
        Runtime runtime=Runtime.getRuntime();
        if (runtime.totalMemory()-runtime.freeMemory()>runtime.maxMemory()*0.85) stop(run,"GENERATOR_HEAP");
    }
    private void stop(Run run,String reason) {
        if (run.stopped.compareAndSet(false,true)) run.stopReason=reason;
        // No cancellation of queued-but-not-started tasks: each admitted operation must run its cleanup.
        // Transport calls have their own whole-exchange deadline; no further attempt is admitted after STOP.
    }
    public synchronized Map<String,Object> stop(String id) {
        Run run=required(id);if (active==run) stop(run,"USER_STOP");return status(id);
    }

    public synchronized String discover(String id) throws IOException {
        Run run=required(id);
        if (active!=null) throw new IllegalStateException("Wait until the active run finishes before discovery");
        if (client.openCalls()!=0) throw new IllegalStateException("Wait until previous HTTP tasks close before discovery");
        if (run.discovering) throw new IllegalStateException("Discovery already admitted; its lifetime budget cannot reset");
        String problem=settings.observerProblem();if (problem!=null) throw new IllegalStateException(problem);
        if (!slot.acquire(run.id)) throw new IllegalStateException("Run "+slot.owner()+" is still active");
        try { Files.writeString(run.dir.resolve("discovery.started"),Instant.now().toString(),StandardOpenOption.CREATE_NEW); }
        catch (IOException | RuntimeException e) { slot.release(run.id);throw e; }
        run.discovering=true;run.stopped.set(false);run.stopReason=null;run.state="DISCOVERING";active=run;
        threads.submit(() -> {
            try {
                long deadline=System.nanoTime()+10_000_000_000L;
                for (Operation op:run.operations) if (op.hold && op.outcome.equals("UNKNOWN")) {
                    String observer=settings.observerProblem();if (observer!=null) { stop(run,observer);break; }
                    invoke(run,op,deadline,"discovery",2);
                    if (run.stopped.get()) break;
                }
                synchronized(this) {
                    run.state=run.stopped.get() ? "STOPPED" : "COMPLETED";finish(run);active=null;slot.release(run.id);
                }
            } catch (Exception e) {
                synchronized(this) { run.state="FAILED";run.error="DISCOVERY_FAILED";active=null;slot.release(run.id); }
            }
        });
        return id;
    }

    public synchronized List<String> list() { return runs.values().stream().map(r -> r.id+" "+r.state).toList(); }
    public synchronized Map<String,Object> status(String id) {
        Run run=required(id);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("runId",id);result.put("state",run.state);result.put("request",run.spec);result.put("startedAt",run.startedAt.toString());
        result.put("inFlight",run.inFlight.get());result.put("attemptsSoFar",run.attempts.size());
        result.put("openHttpTasks",client.openCalls());
        result.put("offeredSoFar",run.operations.stream().filter(op -> !op.outcome.equals("NOT_STARTED")).count());
        if (run.eventId!=null) result.put("eventId",run.eventId.toString());
        if (run.stopReason!=null) result.put("stopReason",run.stopReason);
        if (run.error!=null) result.put("error",run.error);
        if (run.report!=null) result.put("report",run.report);
        return result;
    }
    private Run required(String id) {
        Run run=runs.get(id);if (run==null) throw new NoSuchElementException("Unknown run");return run;
    }
    private void finish(Run run) throws IOException {
        Map<String,Object> report=new LinkedHashMap<>();
        List<Attempt> measured=run.attempts.stream().filter(a -> a.phase.equals("measured")).toList();
        report.put("scheduled",run.spec.total());report.put("initialArrivalRate",run.spec.rate());
        report.put("offered",run.operations.stream().filter(op -> !op.outcome.equals("NOT_STARTED")).count());
        report.put("attempts",measured.size());report.put("discoveryAttempts",run.attempts.size()-measured.size());
        report.put("maxInFlight",run.peak.get());report.put("virtualThreadAttempts",measured.stream().filter(Attempt::virtualThread).count());
        if (run.measuredEnd==0) run.measuredEnd=System.nanoTime();
        report.put("measurementWallMs",run.measuredStart==0 ? 0 : (run.measuredEnd-run.measuredStart)/1e6);
        report.put("outcomes",counts(run.operations.stream().map(op -> op.outcome).toList()));
        report.put("status",counts(measured.stream().map(a -> Integer.toString(a.status)).toList()));
        report.put("httpLatencyMs",latency(measured.stream().mapToDouble(Attempt::latencyMs).toArray()));
        report.put("logicalLatencyMs",latency(run.operations.stream().filter(op -> !op.outcome.equals("NOT_STARTED")).mapToDouble(op -> op.logicalMs).toArray()));
        Map<Long,Object> seconds=new TreeMap<>();
        for (Attempt a:measured) seconds.computeIfAbsent(a.startedSecond,second -> {
            List<Attempt> bucket=measured.stream().filter(x -> x.startedSecond==second).toList();
            return Map.of("attempts",bucket.size(),"status",counts(bucket.stream().map(x -> Integer.toString(x.status)).toList()),
                    "latencyMs",latency(bucket.stream().mapToDouble(Attempt::latencyMs).toArray()));
        });
        report.put("perSecond",seconds);
        Map<String,Object> kinds=new TreeMap<>();
        for (String kind:List.of("HOLD","AVAILABILITY")) {
            List<Attempt> group=measured.stream().filter(a -> a.kind.equals(kind)).toList();
            if (!group.isEmpty()) kinds.put(kind,Map.of("attempts",group.size(),"status",counts(group.stream().map(a -> Integer.toString(a.status)).toList()),
                    "latencyMs",latency(group.stream().mapToDouble(Attempt::latencyMs).toArray())));
        }
        report.put("kinds",kinds);
        write(run,"attempts.json",run.attempts);
        write(run,"outcomes.json",run.operations.stream().map(op -> {
            Map<String,Object> value=new LinkedHashMap<>();value.put("operation",op.index);value.put("outcome",op.outcome);
            value.put("attempts",op.attempts);if (op.bookingId!=null) value.put("bookingId",op.bookingId);return value;
        }).toList());
        run.report=report;write(run,"report.json",status(run.id));
    }
    static Map<String,Long> counts(List<String> values) {
        Map<String,Long> result=new TreeMap<>();values.forEach(v -> result.merge(v,1L,Long::sum));return result;
    }
    static Map<String,Double> latency(double[] values) {
        if (values.length==0) return Map.of();Arrays.sort(values);
        return Map.of("avg",Arrays.stream(values).average().orElse(0),"p50",percentile(values,.50),
                "p95",percentile(values,.95),"p99",percentile(values,.99),"max",values[values.length-1]);
    }
    private static double percentile(double[] values,double q) { return values[(int)Math.ceil(values.length*q)-1]; }
    private synchronized void journal(Run run,Object entry) throws IOException {
        byte[] bytes=(mapper.writeValueAsString(entry)+"\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel=FileChannel.open(run.dir.resolve("sent.jsonl"),StandardOpenOption.CREATE,StandardOpenOption.APPEND,StandardOpenOption.WRITE)) {
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while (buffer.hasRemaining()) channel.write(buffer);channel.force(true);
        }
    }
    private void write(Run run,String file,Object value) throws IOException {
        Files.writeString(run.dir.resolve(file),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
    @PreDestroy void close() {
        Run run=active;if (run!=null) stop(run,"SHUTDOWN");watcher.shutdownNow();threads.shutdownNow();
    }
}

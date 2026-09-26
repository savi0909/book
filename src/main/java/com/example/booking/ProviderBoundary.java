package com.example.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** One HTTP send invocation, no queued calls and no application retry loop. */
@Component
public class ProviderBoundary {
    private final String url;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build();
    private final Semaphore slots=new Semaphore(2);
    private String state="CLOSED";
    private int failures;
    private long reopenAt, generation, calls, rejected;
    private boolean probe;
    public ProviderBoundary(@Value("${lab.provider-url:}") String url) {this.url=url;}
    boolean enabled() {return !url.isBlank();}
    synchronized long enter() {
        if(state.equals("OPEN")) {
            if(System.nanoTime()<reopenAt) {rejected++;throw new Unavailable("CIRCUIT_OPEN");}
            state="HALF_OPEN";
        }
        if(probe || !slots.tryAcquire()) {rejected++;throw new Unavailable("BULKHEAD_FULL");}
        if(state.equals("HALF_OPEN")) probe=true;
        calls++;
        return generation;
    }
    synchronized void finish(long epoch,boolean success) {
        slots.release();
        if(epoch!=generation) return; // A stale CLOSED completion cannot close a newer OPEN generation.
        if(success) {
            failures=0;
            if(state.equals("HALF_OPEN")) {state="CLOSED";probe=false;generation++;}
        } else if(state.equals("HALF_OPEN") || ++failures>=3) {
            state="OPEN";probe=false;reopenAt=System.nanoTime()+Duration.ofSeconds(2).toNanos();generation++;
        }
    }
    String call(String path,String body) {
        long epoch=enter();boolean success=false;
        try {
            var request=HttpRequest.newBuilder(URI.create(url+path)).timeout(Duration.ofMillis(400))
                .method(body==null?"GET":"POST",body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) throw new Unavailable("PROVIDER_HTTP_"+response.statusCode());
            success=true;return response.body();
        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new Unavailable("INTERRUPTED");}
        catch(java.io.IOException e) {throw new Unavailable("PROVIDER_TIMEOUT_OR_IO");}
        finally {finish(epoch,success);}
    }
    public synchronized Map<String,Object> status() {
        return Map.of("enabled",enabled(),"state",state,"inFlight",2-slots.availablePermits(),
            "limitPerProcess",2,"queueCapacity",0,"probeInFlight",probe,"calls",calls,"rejected",rejected);
    }
    static class Unavailable extends RuntimeException {Unavailable(String reason){super(reason);}}
}

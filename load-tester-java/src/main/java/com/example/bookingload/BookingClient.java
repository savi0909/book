package com.example.bookingload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class BookingClient {
    record Reply(int status, JsonNode body, String retryAfter, double latencyMs, boolean closed) {}
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http;
    private final LoaderSettings settings;
    private final ObjectMapper mapper;
    private final AtomicInteger openCalls=new AtomicInteger();

    BookingClient(LoaderSettings settings,ObjectMapper mapper) {
        this.settings=settings; this.mapper=mapper;
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).executor(threads).build();
    }

    Reply call(HttpMethod method,String path,Map<String,Object> body,String key,long timeoutMs) throws InterruptedException {
        long started=System.nanoTime();
        CountDownLatch closed=new CountDownLatch(1);
        Future<Reply> request=threads.submit(() -> {
            openCalls.incrementAndGet();
            try {
                JdkClientHttpRequestFactory factory=new JdkClientHttpRequestFactory(http);
                factory.setReadTimeout(Duration.ofMillis(timeoutMs));
                RestClient client=RestClient.builder().baseUrl(settings.target().toString()).requestFactory(factory).build();
                RestClient.RequestBodySpec spec=client.method(method).uri(path);
                if (key != null) spec.header("Idempotency-Key",key);
                if (settings.ingressToken() != null && !settings.ingressToken().isBlank())
                    spec.header("X-Hold-Lab-Token",settings.ingressToken());
                if (body != null) spec.contentType(MediaType.APPLICATION_JSON).body(body);
                return spec.exchange((req,response) -> {
                    byte[] bytes=response.getBody().readNBytes(65537);
                    if (bytes.length>65536) throw new IOException("Response exceeds 64 KiB");
                    JsonNode json;
                    try { json=bytes.length==0 ? mapper.createObjectNode() : mapper.readTree(bytes); }
                    catch (IOException e) { json=mapper.createObjectNode(); }
                    return new Reply(response.getStatusCode().value(),json,response.getHeaders().getFirst("Retry-After"),elapsed(started),true);
                });
            } finally { openCalls.decrementAndGet();closed.countDown(); }
        });
        try { return request.get(timeoutMs,TimeUnit.MILLISECONDS); }
        catch (ExecutionException e) { return new Reply(0,mapper.createObjectNode(),null,elapsed(started),true); }
        catch (TimeoutException e) {
            request.cancel(true);
            // Do not retry while a prior HTTP task is still open. Server commit can remain ambiguous even after closure.
            boolean ended=closed.await(100,TimeUnit.MILLISECONDS);
            return new Reply(0,mapper.createObjectNode(),null,elapsed(started),ended);
        } catch (InterruptedException e) { request.cancel(true); throw e; }
    }
    private static double elapsed(long start) { return (System.nanoTime()-start)/1e6; }
    int openCalls() { return openCalls.get(); }
    @PreDestroy void close() { threads.shutdownNow();http.shutdownNow(); }
}

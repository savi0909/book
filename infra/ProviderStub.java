import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent, bounded local dependency. No booking database and no money. */
public class ProviderStub implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor=new ThreadPoolExecutor(8,8,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),new ThreadPoolExecutor.AbortPolicy());
    private final Semaphore slots=new Semaphore(2);
    private final Map<String,String> receipts=new HashMap<>();
    private final Path journal;
    private volatile String mode="NORMAL";
    private final AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger(),calls=new AtomicInteger();
    public ProviderStub(int port,Path journal) throws Exception {
        this.journal=journal;
        if(Files.exists(journal)) for(String line:Files.readAllLines(journal)) {
            String[] parts=line.split(" ",2);if(parts.length==2) receipts.put(parts[0],parts[1]);
        }
        server=HttpServer.create(new InetSocketAddress(port),32);server.setExecutor(executor);
        server.createContext("/health",e->reply(e,200,"up"));
        server.createContext("/control",e->{
            if(!e.getRequestMethod().equals("POST")) {reply(e,405,"POST required");return;}
            String next=e.getRequestURI().getQuery();
            if(!Set.of("NORMAL","SLOW","UNAVAILABLE","LOSS").contains(next==null?"":next)) {reply(e,400,"invalid mode");return;}
            mode=next;reply(e,200,next);
        });
        server.createContext("/stats",e->{synchronized(receipts){reply(e,200,"{\"active\":"+active+",\"maximum\":"+maximum+",\"calls\":"+calls+",\"receipts\":"+receipts.size()+",\"mode\":\""+mode+"\"}");}});
        server.createContext("/payments/",this::payment);server.start();
    }
    private void payment(HttpExchange e) throws java.io.IOException {
        if(!slots.tryAcquire()) {reply(e,503,"full");return;}
        int current=active.incrementAndGet();maximum.accumulateAndGet(current,Math::max);calls.incrementAndGet();
        try {
            String failure=mode;
            if(failure.equals("UNAVAILABLE")) {reply(e,503,"unavailable");return;}
            String id=e.getRequestURI().getPath().substring("/payments/".length());UUID.fromString(id);
            String value;
            synchronized(receipts) {
                if(e.getRequestMethod().equals("POST")) {
                    byte[] bytes=e.getRequestBody().readNBytes(129);
                    if(bytes.length>128) {reply(e,400,"too large");return;}
                    String input=new String(bytes,StandardCharsets.UTF_8);
                    String[] fields=input.split(",");
                    if(fields.length!=2 || !Set.of("SUCCESS","FAILURE").contains(fields[0])) {reply(e,400,"invalid");return;}
                    Long.parseLong(fields[1]);
                    value=receipts.get(id);
                    if(value!=null && !value.equals(input)) {reply(e,409,"identity conflict");return;}
                    if(value==null) {
                        try(var file=FileChannel.open(journal,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)) {
                            var buffer=ByteBuffer.wrap((id+" "+input+"\n").getBytes(StandardCharsets.UTF_8));
                            while(buffer.hasRemaining()) file.write(buffer);file.force(true);
                        }
                        receipts.put(id,input);
                    }
                } else if(!e.getRequestMethod().equals("GET")) {reply(e,405,"invalid method");return;}
                value=receipts.get(id);
            }
            // Intentional remote continuation after caller timeout, bounded to two slots and 1500ms.
            if(failure.equals("SLOW") || failure.equals("LOSS")) Thread.sleep(1500);
            if(failure.equals("LOSS")) {e.close();return;}
            reply(e,value==null?404:200,value==null?"absent":value);
        } catch(InterruptedException ex) {Thread.currentThread().interrupt();e.close();}
        catch(IllegalArgumentException ex) {reply(e,400,"invalid input");}
        finally {active.decrementAndGet();slots.release();e.close();}
    }
    private static void reply(HttpExchange e,int status,String value) throws java.io.IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length);
        try(var output=e.getResponseBody()){output.write(bytes);}
    }
    public int port(){return server.getAddress().getPort();}
    public void close(){server.stop(0);executor.shutdownNow();}
    public static void main(String[] args) throws Exception {
        var stub=new ProviderStub(8121,Path.of("/data/receipts.log"));
        Runtime.getRuntime().addShutdownHook(new Thread(stub::close));
    }
}

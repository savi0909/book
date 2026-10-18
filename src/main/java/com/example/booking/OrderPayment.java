package com.example.booking;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import static com.example.booking.OrderMessages.*;

@Service
public class OrderPayment {
    private final OrderStore store;
    private final RestClient provider;
    private final int maxAttempts;
    public OrderPayment(OrderStore store,@Value("${order.provider-url:http://localhost:8136}") String url,
        @Value("${order.provider-token:}") String token,@Value("${order.max-payment-attempts:10}") int maxAttempts) {
        this.store=store;this.maxAttempts=maxAttempts;
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        provider=RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("Authorization","Bearer "+token).build();
    }
    public void consume(Envelope e) {
        e.validate();
        if(!Set.of("CapturePayment","CancelPayment","RefundPayment","QueryPayment").contains(e.type()))
            throw new IllegalArgumentException("Unsupported payment command");
        store.db.tx(()->{
            store.validateCommand(e);if(!store.inbox("payment",e))return null;
            store.advisory("order/payment/"+e.orderId());
            UUID capture=UUID.fromString(e.payload().path("captureCommandId").asText());
            var rows=store.db.jdbc().queryForList("SELECT * FROM order_payment_operations WHERE id=? FOR UPDATE",capture);
            Map<String,Object> c=rows.isEmpty()?null:rows.getFirst();
            if(e.type().equals("CapturePayment") && c==null)insert(e,capture,capture,"CAPTURE","PENDING",false);
            if(e.type().equals("CancelPayment")) {
                if(c==null)insert(e,capture,capture,"CAPTURE","CANCELLED",true);
                else {
                    // Attempts > 0 may have reached the provider. They must reconcile under the same identity.
                    String state=c.get("status").toString();
                    if(((Number)c.get("attempts")).intValue()==0 && state.equals("PENDING"))state="CANCELLED";
                    store.db.jdbc().update("UPDATE order_payment_operations SET stop_requested=true,status=?,next_action_at=CASE WHEN ?='CANCELLED' THEN NULL ELSE next_action_at END WHERE id=?",state,state,capture);
                }
            }
            if(e.type().equals("RefundPayment")) {
                if(c==null || !Set.of("CAPTURED","REFUNDED").contains(c.get("status"))) {
                    store.reply(e,"PaymentResult",store.json.createObjectNode().put("state","UNKNOWN"));return null;
                }
                insert(e,identity(capture,"refund"),capture,"REFUND","PENDING",true);
            }
            reply(e);return null;
        });
    }
    private void insert(Envelope e,UUID id,UUID capture,String operation,String status,boolean stop) {
        store.db.jdbc().update("""
            INSERT INTO order_payment_operations(id,order_id,capture_command_id,operation_type,provider_key,request_hash,amount_minor,currency,status,stop_requested,next_action_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,CASE WHEN ?='PENDING' THEN clock_timestamp() ELSE NULL END) ON CONFLICT DO NOTHING
            """,id,e.orderId(),capture,operation,id.toString(),OrderStore.hash(store.encode(e.payload())),e.payload().path("amountMinor").asLong(),e.payload().path("currency").asText(),status,stop,status);
    }
    private void reply(Envelope e) {
        var rows=store.db.jdbc().queryForList("SELECT * FROM order_payment_operations WHERE order_id=? ORDER BY operation_type DESC",e.orderId());
        String state="UNKNOWN",providerId=null;
        for(var row:rows) {
            if(row.get("operation_type").equals("CAPTURE")){state=row.get("status").toString();providerId=(String)row.get("provider_id");}
        }
        for(var row:rows)if(row.get("operation_type").equals("REFUND")) {
            if(row.get("status").equals("REFUNDED"))state="REFUNDED";
            if(row.get("status").equals("MANUAL_REVIEW"))state="MANUAL_REVIEW";
        }
        var p=store.json.createObjectNode().put("state",state);if(providerId!=null)p.put("providerId",providerId);
        store.reply(e,"PaymentResult",p);
    }
    public int tick() {
        var ids=store.db.jdbc().queryForList("""
            SELECT id FROM order_payment_operations WHERE status IN ('PENDING','UNKNOWN','ACTION_REQUIRED')
              AND next_action_at<=clock_timestamp() AND (locked_until IS NULL OR locked_until<=clock_timestamp())
            ORDER BY next_action_at,id LIMIT 10
            """,UUID.class);
        for(UUID id:ids)process(id);return ids.size();
    }
    public boolean process(UUID id) {
        UUID token=UUID.randomUUID();
        var rows=store.db.tx(()->store.db.jdbc().queryForList("""
            UPDATE order_payment_operations SET lock_token=?,locked_until=clock_timestamp()+interval '15 seconds',attempts=attempts+1,
              status='UNKNOWN',updated_at=clock_timestamp()
            WHERE id=? AND status IN ('PENDING','UNKNOWN','ACTION_REQUIRED') AND next_action_at<=clock_timestamp()
              AND (locked_until IS NULL OR locked_until<=clock_timestamp()) RETURNING *
            """,token,id));
        if(rows.isEmpty())return false;
        var op=rows.getFirst();
        try {
            JsonNode result;
            // A committed operation precedes RPC. Lookup first closes crash-after-provider-commit.
            try {result=provider.get().uri("/mock-pg/saga/operations/{id}",id).retrieve().body(JsonNode.class);}
            catch(org.springframework.web.client.HttpClientErrorException.NotFound absent) {
                if(((Number)op.get("attempts")).intValue()>maxAttempts)throw new IllegalStateException("Provider reconciliation budget exhausted");
                var request=new LinkedHashMap<String,Object>();request.put("orderId",op.get("order_id"));
                request.put("operationType",op.get("operation_type"));request.put("amountMinor",op.get("amount_minor"));request.put("currency",op.get("currency"));
                if(op.get("operation_type").equals("REFUND"))request.put("captureOperationId",op.get("capture_command_id"));
                result=provider.post().uri("/mock-pg/saga/operations/{id}",id).body(request).retrieve().body(JsonNode.class);
            }
            if(result==null || !id.toString().equals(result.path("operationId").asText()) ||
                !op.get("order_id").toString().equals(result.path("orderId").asText()) ||
                !op.get("operation_type").equals(result.path("operationType").asText()) ||
                ((Number)op.get("amount_minor")).longValue()!=result.path("amountMinor").asLong() ||
                !op.get("currency").equals(result.path("currency").asText()))throw new IllegalArgumentException("Provider response mismatch");
            String state=result.path("status").asText();
            if(!Set.of("CAPTURED","DECLINED","ACTION_REQUIRED","REFUNDED","REJECTED").contains(state))throw new IllegalArgumentException("Unknown provider state");
            if(state.equals("REJECTED"))state="MANUAL_REVIEW";
            if(state.equals("ACTION_REQUIRED") && ((Number)op.get("attempts")).intValue()>=maxAttempts)state="MANUAL_REVIEW";
            complete(op,token,state,result.path("providerId").asText(null));
        } catch(Exception error) {
            boolean exhausted=((Number)op.get("attempts")).intValue()>=maxAttempts;
            complete(op,token,exhausted?"MANUAL_REVIEW":"UNKNOWN",null);
        }
        return true;
    }
    private void complete(Map<String,Object> op,UUID token,String state,String providerId) {
        store.db.tx(()->{
            // Serialize completion with CancelPayment; an old external call cannot overwrite a newer owner.
            store.advisory("order/payment/"+op.get("order_id"));
            int changed=store.db.jdbc().update("""
                UPDATE order_payment_operations SET status=?,provider_id=COALESCE(?,provider_id),lock_token=NULL,locked_until=NULL,
                  next_action_at=CASE WHEN ? IN ('UNKNOWN','ACTION_REQUIRED') THEN clock_timestamp()+interval '2 seconds' ELSE NULL END,
                  updated_at=clock_timestamp() WHERE id=? AND lock_token=? AND locked_until>clock_timestamp()
                """,state,providerId,state,op.get("id"),token);
            if(changed==0)return null;
            UUID command=op.get("operation_type").equals("CAPTURE")?(UUID)op.get("capture_command_id"):identity((UUID)store.saga((UUID)op.get("order_id"),false).get("id"),"RefundPayment");
            var commands=store.db.jdbc().queryForList("SELECT * FROM order_commands WHERE id=?",command);
            if(!commands.isEmpty()) {
                var c=commands.getFirst();UUID saga=(UUID)c.get("saga_id");
                var synthetic=new Envelope(identity(token,"result"),c.get("type").toString(),1,(UUID)op.get("order_id"),saga,command,saga,null,store.now(),store.node(c.get("payload").toString()));
                reply(synthetic);
            }
            return null;
        });
    }
}

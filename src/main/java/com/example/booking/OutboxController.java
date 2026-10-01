package com.example.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Local deterministic controls; no sends, payload overrides, reset or deletion. */
@RestController
@RequestMapping("/api/demo/outbox")
@ConditionalOnProperty(name="lab.outbox-controls-enabled",havingValue="true")
public class OutboxController {
    private final OutboxDispatcher dispatcher;
    private final LocalNotificationSink sink;
    private final OutboxWorker worker;
    public OutboxController(OutboxDispatcher dispatcher,LocalNotificationSink sink,OutboxWorker worker) {
        this.dispatcher=dispatcher;this.sink=sink;this.worker=worker;
    }
    record Delay(@NotNull @Min(0) @Max(10000) Integer delayMs) { }
    @GetMapping("/controls") Map<String,Object> controls() {return Map.of("automaticDispatch",worker.automatic(),"waitingEvents",dispatcher.waitingEvents());}
    @PostMapping("/tick") Map<String,Object> tick() {return Map.of("candidates",dispatcher.dispatchBatch());}
    @PostMapping("/events/{id}/delay") Map<String,Object> delay(@PathVariable UUID id,@Valid @RequestBody Delay request) {return dispatcher.delay(id,request.delayMs());}
    @PostMapping("/events/{id}/dispatch") Map<String,Object> dispatch(@PathVariable UUID id) {
        if(dispatcher.eventMissing(id)) throw ApiException.missing();
        return Map.of("eventId",id,"claimed",dispatcher.dispatch(id,true));
    }
    @PostMapping("/events/{id}/consume") Map<String,Object> consume(@PathVariable UUID id) {return sink.consume(id);}
}

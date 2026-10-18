package com.example.booking;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/api/orders")
@ConditionalOnProperty(name="order.enabled",havingValue="true")
public class OrderController {
    private final OrderService service;
    private final String token;
    public OrderController(OrderService service,@Value("${order.api-token:}") String token) {this.service=service;this.token=token;}
    private void authorize(String authorization) {
        if(token.isBlank())throw new ApiException(503,"ORDER_AUTH_NOT_CONFIGURED","Configure the reference API token");
        if(!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),authorization.getBytes(StandardCharsets.UTF_8)))
            throw new ApiException(401,"UNAUTHORIZED","Invalid bearer token");
    }
    @PostMapping public ResponseEntity<String> create(@RequestHeader("Authorization") String auth,@RequestHeader("X-Customer-Id") UUID customer,
        @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody OrderMessages.Create request) {
        authorize(auth);return ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON).body(service.create(customer,key,request));
    }
    @GetMapping("/{id}") public Map<String,Object> get(@RequestHeader("Authorization") String auth,@RequestHeader("X-Customer-Id") UUID customer,@PathVariable UUID id) {
        authorize(auth);return service.get(customer,id);
    }
    @PostMapping("/{id}/cancel") public ResponseEntity<?> cancel(@RequestHeader("Authorization") String auth,@RequestHeader("X-Customer-Id") UUID customer,@PathVariable UUID id) {
        authorize(auth);service.cancel(customer,id);return ResponseEntity.accepted().body(service.get(customer,id));
    }
}

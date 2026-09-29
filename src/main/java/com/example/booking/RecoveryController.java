package com.example.booking;

import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class RecoveryController {
    private final RecoveryIsolation isolation;
    public RecoveryController(RecoveryIsolation isolation) {this.isolation=isolation;}
    @GetMapping("/api/payments/{id}/recovery/history") List<Map<String,Object>> history(@PathVariable UUID id) {return isolation.history(id);}
    @GetMapping("/api/recovery/status") Map<String,Object> status() {return isolation.summary();}
}

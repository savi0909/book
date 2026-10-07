package com.example.bookingload;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/load/movie-runs")
public class MovieRunController {
    private final MovieRunService service;
    MovieRunController(MovieRunService service) { this.service=service; }
    @PostMapping ResponseEntity<?> start(@RequestBody(required=false) MovieSpec request) throws IOException {
        String id=service.start(request);return ResponseEntity.accepted().body(Map.of("runId",id,"status","/load/movie-runs/"+id));
    }
    @GetMapping List<String> list() { return service.list(); }
    @GetMapping("/{id}") Map<String,Object> status(@PathVariable String id) { return service.status(id); }
    @PostMapping("/{id}/stop") Map<String,Object> stop(@PathVariable String id) { return service.stop(id); }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<?> busy(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("error",e.getMessage()));
    }
    @ExceptionHandler(NoSuchElementException.class) ResponseEntity<?> missing() { return ResponseEntity.notFound().build(); }
}

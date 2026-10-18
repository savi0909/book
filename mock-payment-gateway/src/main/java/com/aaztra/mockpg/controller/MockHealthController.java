package com.aaztra.mockpg.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class MockHealthController {
    private final JdbcTemplate jdbc;
    public MockHealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping("/mock-pg/health")
    public Map<String, String> health() {
        jdbc.queryForObject("select 1", Integer.class);
        return Map.of("status", "UP");
    }
}

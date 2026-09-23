package com.example.booking;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class AdmissionFilter extends OncePerRequestFilter {
    private final AdmissionGate gate;
    private final String instance;
    public AdmissionFilter(AdmissionGate gate, @Value("${lab.instance}") String instance) {
        this.gate = gate;
        this.instance = instance;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Booking-Instance", instance);
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || path.startsWith("/api/demo/failover/")) {
            chain.doFilter(request, response);
            return;
        }
        if (!gate.enter()) {
            response.setStatus(503);
            response.setHeader("Retry-After", "1");
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"INSTANCE_DRAINING\",\"message\":\"Retry a keyed operation through the shared endpoint\"}");
            return;
        }
        try { chain.doFilter(request, response); }
        finally { gate.leave(); }
    }
}

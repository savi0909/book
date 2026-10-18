package com.aaztra.mockpg.saga;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.server.RequestPath;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class SagaTokenFilter extends OncePerRequestFilter {
    private static final PathPattern SAGA_PATH = PathPatternParser.defaultInstance.parse("/mock-pg/saga/{*path}");
    private final String token;
    public SagaTokenFilter(@Value("${mock-pg.saga-token:}") String token) { this.token = token; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        // Match the same parsed segments as Spring MVC: matrix parameters are ignored and
        // encoded segment values are decoded without turning encoded slashes into separators.
        return !SAGA_PATH.matches(RequestPath.parse(request.getRequestURI(), request.getContextPath())
                .pathWithinApplication());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        if (token.isBlank()) { refuse(response, 503, "Saga provider token is not configured"); return; }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8))) {
            refuse(response, 401, "Invalid saga provider token"); return;
        }
        chain.doFilter(request, response);
    }
    private void refuse(HttpServletResponse response, int status, String reason) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + reason + "\"}");
    }
}

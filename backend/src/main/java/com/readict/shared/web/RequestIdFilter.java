package com.readict.shared.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestIdFilter extends OncePerRequestFilter {
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    public static String id(HttpServletRequest request) { return (String) request.getAttribute(ATTRIBUTE); }
    public static String normalize(String candidate) {
        return candidate != null && UUID_PATTERN.matcher(candidate).matches()
                ? UUID.fromString(candidate).toString() : UUID.randomUUID().toString();
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = id(request);
        if (id == null) { id = normalize(request.getHeader("X-Request-Id")); request.setAttribute(ATTRIBUTE, id); }
        response.setHeader("X-Request-Id", id);
        MDC.put("requestId", id);
        long start = System.nanoTime();
        try { chain.doFilter(request, response); }
        finally {
            String route = switch (request.getRequestURI()) {
                case "/api/v1/system/status", "/actuator/health/liveness", "/actuator/health/readiness" -> request.getRequestURI();
                default -> "other";
            };
            String method = java.util.Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(request.getMethod()) ? request.getMethod() : "OTHER";
            LOG.info("method={} route={} status={} durationMs={} code={}", method, route, response.getStatus(),
                    (System.nanoTime() - start) / 1_000_000, request.getAttribute("errorCode"));
            MDC.remove("requestId");
        }
    }
}

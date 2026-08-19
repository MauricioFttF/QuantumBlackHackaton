package com.seuprojeto.backend.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;

/**
 * Applies {@link RateLimiter} to the endpoints that spend AI quota.
 *
 * <p>Runs as a filter rather than an interceptor so the request is rejected before any
 * controller, service or embedding call executes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Only the endpoints that cost money. GET /api/chunks is a cheap database read. */
    private static final Set<String> LIMITED_PATHS = Set.of("/api/chat", "/api/ingest");

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // CORS preflight carries no credentials or payload and must never be throttled,
        // or the browser reports a misleading CORS error instead of a 429.
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Instant now = Instant.now();
        RateLimiter.Decision decision = rateLimiter.tryAcquire(clientId(request), now);

        if (decision.allowed()) {
            rateLimiter.evictIdleClients(now);
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = decision.retryAfter().toSeconds();
        log.warn("Rate limit hit ({}) for {} {}; retry after {}s",
                decision.scope(), request.getMethod(), request.getRequestURI(), retryAfterSeconds);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Limite de requisições (%s) atingido. Tente novamente em %d segundo(s)."
                        .formatted(decision.scope(), retryAfterSeconds));
        problem.setTitle("Muitas requisições");
        problem.setInstance(java.net.URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    /**
     * Client identity for throttling. {@code X-Forwarded-For} is honoured so a reverse proxy
     * does not collapse every user into one bucket — it is spoofable, but this limit protects
     * a quota, it is not a security control.
     */
    private static String clientId(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}

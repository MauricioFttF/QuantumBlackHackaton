package com.seuprojeto.backend.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seuprojeto.backend.config.RateLimitProperties;
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

    /**
     * Sign-in and registration. Throttled separately from the AI endpoints: these cost a BCrypt
     * comparison rather than provider quota, and the reason to limit them is guessing, not spend.
     */
    private static final Set<String> AUTH_PATHS = Set.of("/api/auth/login", "/api/auth/register");

    /**
     * Agenda recommendations: one embedding call each, no generation. Limited so one client cannot
     * monopolise the endpoint, but deliberately not charged to the daily generation budget.
     */
    private static final Set<String> RECOMMEND_PATHS = Set.of("/api/agenda/recommend");

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // CORS preflight carries no credentials or payload and must never be throttled,
        // or the browser reports a misleading CORS error instead of a 429.
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !(LIMITED_PATHS.contains(path) || AUTH_PATHS.contains(path)
                        || RECOMMEND_PATHS.contains(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Instant now = Instant.now();
        String path = request.getRequestURI();
        RateLimiter.Decision decision;
        if (AUTH_PATHS.contains(path)) {
            decision = rateLimiter.tryAcquireAuthAttempt(clientId(request), now);
        } else if (RECOMMEND_PATHS.contains(path)) {
            decision = rateLimiter.tryAcquireRecommendation(clientId(request), now);
        } else {
            decision = rateLimiter.tryAcquire(clientId(request), now);
        }

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
     * Client identity for throttling.
     *
     * <p>{@code X-Forwarded-For} is only consulted when {@code app.rate-limit.trust-forwarded-header}
     * is on, because the header is client-supplied: trusting it unconditionally lets a caller send
     * a different value per request and get an unlimited number of per-IP buckets. Enable it only
     * behind a proxy that overwrites the header.
     */
    private String clientId(HttpServletRequest request) {
        if (properties.trustForwardedHeader()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}

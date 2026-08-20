package com.seuprojeto.backend.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seuprojeto.backend.model.AuthenticatedUser;
import com.seuprojeto.backend.service.AuthService;
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
import java.net.URI;
import java.util.Optional;
import java.util.Set;

/**
 * Turns {@code Authorization: Bearer <token>} into an identity, and refuses the endpoints that
 * require one.
 *
 * <p><b>Runs before {@link RateLimitFilter}</b> (order 50 against 100), and that ordering is
 * load-bearing: the rate limiter counts every allowed request against a daily AI budget of 18, so if
 * throttling came first an unauthenticated stranger could burn the whole day's quota on requests
 * that were going to be rejected as 401 anyway.
 *
 * <p>An invalid token is fatal only where authentication is required. On a public path — logging
 * in again with a stale token still in the client — it is ignored rather than answered with a 401
 * the user cannot act on.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class AuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);

    /** Where {@link CurrentUser} reads the resolved identity from. */
    static final String AUTHENTICATED_USER = AuthenticationFilter.class.getName() + ".user";

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Endpoints that need an account. {@code GET /api/chunks} stays open: it is a read-only
     * debugging view that costs no AI quota and exposes only the corpus, which is public material.
     */
    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/chat", "/api/chat/history", "/api/ingest", "/api/auth/me",
            "/api/agenda/recommend");

    private final AuthService authService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthenticationFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Preflight carries no Authorization header by definition; answering it with a 401 shows up
        // in the browser as a CORS failure and hides the real problem.
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Optional<AuthenticatedUser> user = bearerToken(request).flatMap(authService::authenticate);
        user.ifPresent(value -> request.setAttribute(AUTHENTICATED_USER, value));

        if (user.isEmpty() && PROTECTED_PATHS.contains(request.getRequestURI())) {
            log.debug("Rejected unauthenticated {} {}", request.getMethod(), request.getRequestURI());
            unauthorized(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private void unauthorized(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Faça login para usar este recurso.");
        problem.setTitle("Não autenticado");
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // Says what kind of credential is expected, so a client is not left guessing.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}

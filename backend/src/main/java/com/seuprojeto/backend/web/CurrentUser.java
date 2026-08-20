package com.seuprojeto.backend.web;

import com.seuprojeto.backend.model.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Answers "who is asking?" for one request. Still the only place in the application that decides
 * this — it now reads the identity {@link AuthenticationFilter} resolved from the bearer token,
 * where it used to read a client-supplied {@code X-User-Id} header.
 *
 * <p>Reading the request attribute rather than re-resolving the token keeps authentication to one
 * database lookup per request, and means a controller cannot accidentally trust something the
 * filter rejected.
 */
@Component
public class CurrentUser {

    /** Empty only where authentication is optional; protected paths never reach a controller. */
    public Optional<AuthenticatedUser> current(HttpServletRequest request) {
        Object attribute = request.getAttribute(AuthenticationFilter.AUTHENTICATED_USER);
        return attribute instanceof AuthenticatedUser user ? Optional.of(user) : Optional.empty();
    }

    /**
     * The identity behind a request on a protected path.
     *
     * @throws IllegalStateException if there is none — that means the path was left out of
     *         {@code AuthenticationFilter.PROTECTED_PATHS}, which is a wiring bug, not a client
     *         error, and must not be answered as though the user did something wrong
     */
    public AuthenticatedUser require(HttpServletRequest request) {
        return current(request).orElseThrow(() -> new IllegalStateException(
                "No authenticated user on " + request.getRequestURI()
                        + "; the path is missing from AuthenticationFilter.PROTECTED_PATHS"));
    }

    /** The key {@code ConversationMemory} stores this caller's history under. */
    public String conversationKey(HttpServletRequest request) {
        return current(request).map(AuthenticatedUser::conversationKey).orElse(null);
    }
}

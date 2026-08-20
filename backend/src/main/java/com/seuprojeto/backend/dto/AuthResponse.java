package com.seuprojeto.backend.dto;

import java.time.Instant;

/**
 * Response of {@code POST /api/auth/register} and {@code POST /api/auth/login}.
 *
 * <p>{@code token} is the only time the session token exists outside the client: the server keeps
 * just its hash. Losing it means logging in again, which is the intended failure mode.
 *
 * @param expiresAt when the session stops working, so a client can decide to re-authenticate
 *                  before a request fails
 */
public record AuthResponse(String token, Instant expiresAt, String email) {

    @Override
    public String toString() {
        return "AuthResponse[email=%s, expiresAt=%s, token=***]".formatted(email, expiresAt);
    }
}

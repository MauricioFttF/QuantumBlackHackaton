package com.seuprojeto.backend.dto;

/**
 * Request body of {@code POST /api/auth/login}.
 *
 * <p>Only emptiness is checked. The registration policy is deliberately <em>not</em> applied: a
 * password that predates a policy change must still be able to sign in, and telling a login
 * attempt about length rules only helps someone probing the endpoint.
 *
 * <p>{@code toString} is overridden so the password cannot reach a log line.
 */
public record LoginRequest(String email, String password) {

    public LoginRequest {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Informe o e-mail e a senha");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Informe o e-mail e a senha");
        }
    }

    @Override
    public String toString() {
        return "LoginRequest[email=%s, password=***]".formatted(email);
    }
}

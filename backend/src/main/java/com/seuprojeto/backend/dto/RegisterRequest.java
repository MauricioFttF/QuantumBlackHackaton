package com.seuprojeto.backend.dto;

/**
 * Request body of {@code POST /api/auth/register}.
 *
 * <p>Shape is checked here; the email is normalised and the password policy applied further in,
 * so both produce the same 400 with a message meant for a person.
 *
 * <p>{@code toString} is overridden because the generated one would print the password, and this
 * object passes through layers that log.
 */
public record RegisterRequest(String email, String password) {

    public RegisterRequest {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("O e-mail não pode ser vazio");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("A senha não pode ser vazia");
        }
    }

    @Override
    public String toString() {
        return "RegisterRequest[email=%s, password=***]".formatted(email);
    }
}

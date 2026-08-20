package com.seuprojeto.backend.service;

import java.nio.charset.StandardCharsets;

/**
 * What counts as an acceptable password at registration. Pure and deterministic.
 *
 * <p>Length only — no composition rules. Forcing a digit and a symbol reliably produces
 * {@code Senha1!} and nothing safer; length is the property that actually costs an attacker
 * anything.
 *
 * <p>The upper bound is not a preference. BCrypt hashes the first 72 <em>bytes</em> of the input
 * and silently ignores the rest, so a longer password would appear to be accepted while everything
 * past byte 72 did nothing — and in Portuguese, accented characters take two bytes each, so the
 * limit arrives sooner than the character count suggests. Refusing is honest; truncating is not.
 * Applied at registration, never at login: an existing account must not become unusable because
 * the policy changed.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /**
     * @throws IllegalArgumentException if the password is unusable, with a message safe to show a
     *         user — it describes the rule, never the value
     */
    public static void validate(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("A senha não pode ser vazia");
        }
        if (password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "A senha deve ter pelo menos %d caracteres".formatted(MIN_LENGTH));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "A senha é longa demais (máximo de %d bytes, e acentos contam como dois)"
                            .formatted(MAX_BYTES));
        }
    }
}

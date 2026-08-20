package com.seuprojeto.backend.model;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A claimed email address, normalised at construction.
 *
 * <p>Normalisation is the point: the address is trimmed and lowercased once, here, so the
 * {@code UNIQUE} constraint on {@code app_user.email} actually means "one account per address"
 * rather than "one account per spelling of an address".
 *
 * <p>The shape check is deliberately conservative rather than RFC 5322 — a full grammar accepts
 * addresses no mail server will take, and the only real proof that an address exists is a
 * confirmation email, which this system does not send yet. So: something, an {@code @}, a dotted
 * domain, no spaces.
 */
public record EmailAddress(String value) {

    /** Matches the column width; the practical maximum for an address. */
    public static final int MAX_LENGTH = 254;

    private static final Pattern SHAPE = Pattern.compile("[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+");

    public EmailAddress {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("O e-mail não pode ser vazio");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "O e-mail não pode ter mais de %d caracteres".formatted(MAX_LENGTH));
        }
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("Informe um e-mail válido");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}

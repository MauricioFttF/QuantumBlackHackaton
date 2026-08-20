package com.seuprojeto.backend.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AppUserTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    @Test
    void constructor_storesTheNormalisedAddress() {
        AppUser user = new AppUser(new EmailAddress("Pedro@USP.br"), "$2a$10$hash", NOW);

        assertThat(user.getEmail()).isEqualTo("pedro@usp.br");
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void constructor_blankHash_isRejectedSoAnAccountCannotExistWithoutAPassword() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new AppUser(new EmailAddress("pedro@usp.br"), "  ", NOW));
    }
}

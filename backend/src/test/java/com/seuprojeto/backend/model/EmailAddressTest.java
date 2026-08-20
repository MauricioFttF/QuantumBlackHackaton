package com.seuprojeto.backend.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class EmailAddressTest {

    @Test
    void constructor_mixedCaseAndPadding_isNormalisedSoUniquenessMeansOneAccountPerAddress() {
        assertThat(new EmailAddress("  Pedro.Goularte@USP.br ").value())
                .isEqualTo("pedro.goularte@usp.br");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "sem-arroba.com",
            "sem@dominio",
            "dois@@arrobas.com",
            "com espaco@dominio.com",
            "@dominio.com",
            "usuario@.com",
    })
    void constructor_malformedAddress_isRejected(String malformed) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new EmailAddress(malformed));
    }

    @Test
    void constructor_blank_isRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new EmailAddress("   "))
                .withMessageContaining("vazio");
    }

    @Test
    void constructor_longerThanTheColumn_isRejectedRatherThanTruncated() {
        String tooLong = "a".repeat(EmailAddress.MAX_LENGTH) + "@exemplo.com";

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new EmailAddress(tooLong));
    }
}

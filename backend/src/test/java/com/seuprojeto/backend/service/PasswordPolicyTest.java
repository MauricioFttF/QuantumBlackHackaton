package com.seuprojeto.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PasswordPolicyTest {

    @Test
    void validate_longEnough_isAccepted() {
        assertThatCode(() -> PasswordPolicy.validate("senha-boa-1")).doesNotThrowAnyException();
    }

    @Test
    void validate_shorterThanTheMinimum_isRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> PasswordPolicy.validate("curta1"))
                .withMessageContaining(String.valueOf(PasswordPolicy.MIN_LENGTH));
    }

    @Test
    void validate_empty_isRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> PasswordPolicy.validate(""));
    }

    @Test
    void validate_pastBcryptsByteLimit_isRejectedRatherThanSilentlyTruncated() {
        // Everything past byte 72 would be ignored by the hash, so accepting it would mean
        // telling the user their long password was used when it was not.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> PasswordPolicy.validate("a".repeat(PasswordPolicy.MAX_BYTES + 1)))
                .withMessageContaining(String.valueOf(PasswordPolicy.MAX_BYTES));
    }

    @Test
    void validate_accentsCountAsTwoBytes_soTheLimitArrivesSoonerThanTheCharacterCount() {
        String fortyCharsOfAccents = "ã".repeat(40);   // 80 bytes in UTF-8

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> PasswordPolicy.validate(fortyCharsOfAccents));
    }
}

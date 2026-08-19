package com.seuprojeto.backend.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ChunkDraftTest {

    @Test
    void contentHash_sameDraft_isStableAcrossInstances() {
        ChunkDraft one = new ChunkDraft("palestrante", "Salim Ismail", "Autor de Exponential Organizations.");
        ChunkDraft two = new ChunkDraft("palestrante", "Salim Ismail", "Autor de Exponential Organizations.");

        assertThat(one.contentHash())
                .isEqualTo(two.contentHash())
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void contentHash_isPinnedToAKnownValue() {
        // Pins the algorithm: if this changes, every stored chunk is orphaned and re-ingested.
        assertThat(new ChunkDraft("evento", "Informações Gerais", "Evento: IA").contentHash())
                .isEqualTo(sha256Of("evento\0Informações Gerais\0Evento: IA"));
    }

    @Test
    void contentHash_differingContent_differs() {
        ChunkDraft base = new ChunkDraft("agenda", "09h00", "Abertura");

        assertThat(base.contentHash())
                .isNotEqualTo(new ChunkDraft("agenda", "09h00", "Encerramento").contentHash())
                .isNotEqualTo(new ChunkDraft("agenda", "10h00", "Abertura").contentHash())
                .isNotEqualTo(new ChunkDraft("materia", "09h00", "Abertura").contentHash());
    }

    @Test
    void contentHash_textMovedAcrossFields_doesNotCollide() {
        // The NUL separator is what prevents this.
        assertThat(new ChunkDraft("agenda", "ab", "cd").contentHash())
                .isNotEqualTo(new ChunkDraft("agenda", "a", "bcd").contentHash());
    }

    @Test
    void contentHash_nullTitleRef_isAllowed() {
        assertThat(new ChunkDraft("agenda", null, "Coffee Break").contentHash()).hasSize(64);
    }

    @Test
    void constructor_blankTypeOrContent_throwsIllegalArgumentException() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ChunkDraft("  ", "ref", "content"))
                .withMessageContaining("type");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ChunkDraft("agenda", "ref", "  "))
                .withMessageContaining("content");
    }

    private static String sha256Of(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}

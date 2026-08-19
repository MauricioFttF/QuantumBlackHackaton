package com.seuprojeto.backend.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A chunk of text extracted from the source JSON, before it has been embedded.
 *
 * <p>This is the "texto" stage of the ingestion pipeline: pure, deterministic, and free of
 * any database or API concern. The same source JSON always produces the same drafts, and
 * therefore the same {@link #contentHash()} — which is what makes ingestion idempotent.
 */
public record ChunkDraft(String type, String titleRef, String content) {

    public ChunkDraft {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Chunk type must not be null or blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Chunk content must not be null or blank");
        }
    }

    /**
     * Stable SHA-256 identity of this draft, as 64 lowercase hex characters.
     *
     * <p>Fields are joined with a NUL separator so that moving text across field boundaries
     * cannot produce a collision.
     */
    public String contentHash() {
        String canonical = type + '\0' + (titleRef == null ? "" : titleRef) + '\0' + content;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to be present on every JVM", e);
        }
    }
}

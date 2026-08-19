package com.seuprojeto.backend.error;

/**
 * Two ingestion runs wrote the same chunks at once.
 *
 * <p>The content-hash check is a read, so concurrent runs can both decide a chunk is new. The
 * unique constraint is what actually guarantees correctness; this exception exists so the loser
 * of that race reports a 409 instead of an opaque 500. Duplicate embedding work is still spent
 * in that window — a reservation row would be needed to avoid it.
 */
public class ConcurrentIngestionException extends RuntimeException {

    public ConcurrentIngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}

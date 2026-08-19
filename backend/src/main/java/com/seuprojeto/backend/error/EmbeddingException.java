package com.seuprojeto.backend.error;

/**
 * Thrown when an embedding cannot be produced. Never swallowed and never replaced by a
 * zero vector: a broken embedding provider must not look like a low-similarity match.
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}

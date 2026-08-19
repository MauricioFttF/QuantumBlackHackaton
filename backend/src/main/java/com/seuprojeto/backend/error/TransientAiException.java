package com.seuprojeto.backend.error;

/**
 * A Gemini failure that is worth retrying: HTTP 429, any 5xx, or a network/timeout error.
 *
 * <p>Separate from {@link EmbeddingException}/{@link GenerationException} so the retry policy
 * can distinguish "try again" from "this will fail identically every time" — a malformed
 * request, a rejected API key, or a safety-blocked answer must not be retried.
 */
public class TransientAiException extends RuntimeException {

    public TransientAiException(String message) {
        super(message);
    }

    public TransientAiException(String message, Throwable cause) {
        super(message, cause);
    }
}

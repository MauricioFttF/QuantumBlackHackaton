package com.seuprojeto.backend.error;

/**
 * Thrown when the model fails to produce a usable answer. A truncated or filtered response
 * counts as a failure: serving half an answer as if it were complete is silent degradation.
 */
public class GenerationException extends RuntimeException {

    public GenerationException(String message) {
        super(message);
    }

    public GenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}

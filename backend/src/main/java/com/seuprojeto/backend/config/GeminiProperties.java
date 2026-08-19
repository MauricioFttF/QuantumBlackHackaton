package com.seuprojeto.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the Gemini API, bound from environment variables.
 *
 * <p>Validation happens here so that a misconfigured deployment fails at startup with a
 * readable message instead of at the first request.
 */
@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties(
        String apiKey,
        String baseUrl,
        String embeddingModel,
        int embeddingDimensions,
        String chatModel,
        double chatTemperature,
        int chatMaxOutputTokens,
        Duration connectTimeout,
        Duration readTimeout,
        Duration chatReadTimeout,
        int retryMaxAttempts,
        Duration retryInitialDelay,
        Duration retryMaxElapsed) {

    public GeminiProperties {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "GEMINI_API_KEY is not set. Copy .env.example to .env and fill in your key "
                            + "(get one at https://aistudio.google.com/apikey).");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("gemini.base-url must not be blank");
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalArgumentException("gemini.embedding-model must not be blank");
        }
        if (embeddingDimensions <= 0) {
            throw new IllegalArgumentException(
                    "gemini.embedding-dimensions must be positive, was " + embeddingDimensions);
        }
        // pgvector's HNSW index supports at most 2000 dimensions, so the model's 3072-dimension
        // default cannot be indexed. Reject it here rather than at index-creation time.
        if (embeddingDimensions > 2000) {
            throw new IllegalArgumentException(
                    "gemini.embedding-dimensions must be <= 2000 to stay indexable by pgvector HNSW, was "
                            + embeddingDimensions);
        }
        if (chatModel == null || chatModel.isBlank()) {
            throw new IllegalArgumentException("gemini.chat-model must not be blank");
        }
        if (!Double.isFinite(chatTemperature) || chatTemperature < 0.0 || chatTemperature > 2.0) {
            throw new IllegalArgumentException(
                    "gemini.chat-temperature must be within [0.0, 2.0], was " + chatTemperature);
        }
        if (chatMaxOutputTokens <= 0) {
            throw new IllegalArgumentException(
                    "gemini.chat-max-output-tokens must be positive, was " + chatMaxOutputTokens);
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("gemini.connect-timeout must be positive");
        }
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("gemini.read-timeout must be positive");
        }
        // Generation is far slower than embedding (seconds vs sub-second), so it gets its own
        // budget instead of being strangled by the embedding timeout.
        if (chatReadTimeout == null || chatReadTimeout.isNegative() || chatReadTimeout.isZero()) {
            throw new IllegalArgumentException("gemini.chat-read-timeout must be positive");
        }
        if (retryMaxAttempts < 1) {
            throw new IllegalArgumentException(
                    "gemini.retry-max-attempts must be at least 1 (1 = no retry), was " + retryMaxAttempts);
        }
        if (retryInitialDelay == null || retryInitialDelay.isNegative()) {
            throw new IllegalArgumentException("gemini.retry-initial-delay must not be negative");
        }
        if (retryMaxElapsed == null || retryMaxElapsed.isNegative() || retryMaxElapsed.isZero()) {
            throw new IllegalArgumentException("gemini.retry-max-elapsed must be positive");
        }
    }
}

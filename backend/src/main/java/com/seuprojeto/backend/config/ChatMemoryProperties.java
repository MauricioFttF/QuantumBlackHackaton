package com.seuprojeto.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Server-side conversation memory for {@code POST /api/chat}.
 *
 * <p>Validation is hand-rolled to match the other properties records in this package;
 * {@code spring-boot-starter-validation} is not on the classpath, so Jakarta annotations here
 * would be silently inert.
 *
 * @param enabled              set false to answer every question with no history at all
 * @param ttl                  how far back history reaches. Also the deletion horizon: reads
 *                             filter on it, so an expired turn cannot influence an answer even
 *                             if the purge task never runs
 * @param maxTurns             most recent turns fed to the model. Bounds the prompt, which is
 *                             billed against the same token budget as the answer
 * @param retrievalContextTurns how many earlier *user* turns join the text that gets embedded, so
 *                             that "e ele fala a que horas?" retrieves something. 0 disables it
 *                             and leaves retrieval byte-identical to the single-turn behaviour
 * @param cleanupInterval      how often expired turns are physically deleted
 */
@ConfigurationProperties(prefix = "app.chat-memory")
public record ChatMemoryProperties(
        boolean enabled,
        Duration ttl,
        int maxTurns,
        int retrievalContextTurns,
        Duration cleanupInterval) {

    public ChatMemoryProperties {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("app.chat-memory.ttl must be positive");
        }
        if (maxTurns <= 0) {
            throw new IllegalArgumentException(
                    "app.chat-memory.max-turns must be positive, was " + maxTurns);
        }
        if (retrievalContextTurns < 0) {
            throw new IllegalArgumentException(
                    "app.chat-memory.retrieval-context-turns must not be negative, was "
                            + retrievalContextTurns);
        }
        if (cleanupInterval == null || cleanupInterval.isNegative() || cleanupInterval.isZero()) {
            throw new IllegalArgumentException("app.chat-memory.cleanup-interval must be positive");
        }
        // A purge that runs less often than the window is still correct (reads filter on the
        // cutoff), but it lets the table grow far past what it should hold. Catch the typo.
        if (cleanupInterval.compareTo(ttl) > 0) {
            throw new IllegalArgumentException(
                    "app.chat-memory.cleanup-interval (%s) must not exceed app.chat-memory.ttl (%s)"
                            .formatted(cleanupInterval, ttl));
        }
    }
}

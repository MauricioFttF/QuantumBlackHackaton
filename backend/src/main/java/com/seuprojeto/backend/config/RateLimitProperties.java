package com.seuprojeto.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate limiting for the endpoints that cost AI quota.
 *
 * <p>Two independent limits, because they protect against different things:
 * {@code requestsPerMinutePerClient} stops one caller monopolising the service, while
 * {@code requestsPerDayTotal} protects the shared daily provider quota — the Gemini free tier
 * allows only 20 generateContent calls per day, so a single enthusiastic user can exhaust the
 * whole event's budget in a couple of minutes.
 *
 * @param enabled                    set false to disable both limits (useful in tests)
 * @param requestsPerMinutePerClient per-IP allowance in a rolling 60s window
 * @param requestsPerDayTotal        global allowance across all clients in a rolling 24h window
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        int requestsPerMinutePerClient,
        int requestsPerDayTotal) {

    public RateLimitProperties {
        if (requestsPerMinutePerClient <= 0) {
            throw new IllegalArgumentException(
                    "app.rate-limit.requests-per-minute-per-client must be positive, was "
                            + requestsPerMinutePerClient);
        }
        if (requestsPerDayTotal <= 0) {
            throw new IllegalArgumentException(
                    "app.rate-limit.requests-per-day-total must be positive, was " + requestsPerDayTotal);
        }
    }
}

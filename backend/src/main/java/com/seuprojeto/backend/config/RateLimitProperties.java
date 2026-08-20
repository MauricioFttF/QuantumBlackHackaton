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
 * @param authRequestsPerMinutePerClient per-IP allowance for login and registration in a rolling
 *                                   60s window. Separate from the AI limits on purpose: sign-in
 *                                   attempts must not consume the daily AI budget (a stranger
 *                                   could otherwise exhaust the day's quota by failing to log in),
 *                                   and they need a different ceiling — enough for a person
 *                                   mistyping a password, not enough to grind through a list
 * @param recommendRequestsPerMinutePerClient per-IP allowance for agenda recommendations.
 *                                   Separate again: a recommendation costs one embedding call and
 *                                   no generation, so it must not draw down the daily budget that
 *                                   exists to protect generateContent quota
 * @param requestsPerMinutePerClient per-IP allowance in a rolling 60s window
 * @param requestsPerDayTotal        global allowance across all clients in a rolling 24h window
 * @param trustForwardedHeader       whether X-Forwarded-For identifies the client. Only enable
 *                                   behind a proxy that overwrites the header: it is
 *                                   client-supplied, so trusting it lets a caller mint a fresh
 *                                   bucket per request and walk straight past the per-IP limit
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        int requestsPerMinutePerClient,
        int requestsPerDayTotal,
        int authRequestsPerMinutePerClient,
        int recommendRequestsPerMinutePerClient,
        boolean trustForwardedHeader) {

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
        if (authRequestsPerMinutePerClient <= 0) {
            throw new IllegalArgumentException(
                    "app.rate-limit.auth-requests-per-minute-per-client must be positive, was "
                            + authRequestsPerMinutePerClient);
        }
        if (recommendRequestsPerMinutePerClient <= 0) {
            throw new IllegalArgumentException(
                    "app.rate-limit.recommend-requests-per-minute-per-client must be positive, was "
                            + recommendRequestsPerMinutePerClient);
        }
    }
}

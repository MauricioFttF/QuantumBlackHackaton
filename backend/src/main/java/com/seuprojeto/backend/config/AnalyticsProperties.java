package com.seuprojeto.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Organizer dashboard settings.
 *
 * @param defaultWindow how far back {@code GET /api/analytics/interest-summary} looks when the
 *                      request omits {@code from}. A default is needed because "all time" on a
 *                      growing log is an unbounded scan
 * @param maxResults    ceiling on returned rows. The response is sorted by demand, so the cap
 *                      drops the long tail rather than anything an organizer is looking for — and
 *                      it is reported when it bites
 */
@ConfigurationProperties(prefix = "analytics")
public record AnalyticsProperties(Duration defaultWindow, int maxResults) {

    public AnalyticsProperties {
        if (defaultWindow == null || defaultWindow.isNegative() || defaultWindow.isZero()) {
            throw new IllegalArgumentException("analytics.default-window must be positive");
        }
        if (maxResults <= 0) {
            throw new IllegalArgumentException(
                    "analytics.max-results must be positive, was " + maxResults);
        }
    }
}

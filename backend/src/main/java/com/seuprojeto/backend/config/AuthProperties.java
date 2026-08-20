package com.seuprojeto.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Authentication settings. Hand-rolled validation, like every other properties record here
 * (§3.1.14 in CLAUDE.md).
 *
 * @param sessionTtl         how long a login lasts. Checked at lookup time, so shortening it takes
 *                           effect for existing sessions immediately
 * @param bcryptStrength     BCrypt cost factor. Each step doubles the work: 10 is ~100ms on this
 *                           hardware, which is the point — it is what makes guessing expensive.
 *                           Raising it re-hashes nothing; existing hashes carry their own cost
 *                           factor and keep verifying
 * @param sessionPurgePeriod how often expired sessions are physically deleted
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        Duration sessionTtl,
        int bcryptStrength,
        Duration sessionPurgePeriod) {

    public AuthProperties {
        if (sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("app.auth.session-ttl must be positive");
        }
        // BCryptPasswordEncoder itself rejects anything outside [4, 31]; 10 is the library default
        // and 4 exists only for tests. Refusing below 10 here keeps a "make the tests faster"
        // change from quietly weakening production hashing.
        if (bcryptStrength < 10 || bcryptStrength > 31) {
            throw new IllegalArgumentException(
                    "app.auth.bcrypt-strength must be within [10, 31], was " + bcryptStrength);
        }
        if (sessionPurgePeriod == null || sessionPurgePeriod.isNegative() || sessionPurgePeriod.isZero()) {
            throw new IllegalArgumentException("app.auth.session-purge-period must be positive");
        }
    }
}

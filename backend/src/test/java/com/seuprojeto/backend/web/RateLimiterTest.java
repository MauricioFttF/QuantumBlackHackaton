package com.seuprojeto.backend.web;

import com.seuprojeto.backend.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-08-18T12:00:00Z");

    @Test
    void tryAcquire_withinPerClientLimit_allows() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 3, 100, false));

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("1.1.1.1", T0).allowed()).as("request %d", i).isTrue();
        }
    }

    @Test
    void tryAcquire_perClientLimitExceeded_refusesWithRetryAfter() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 2, 100, false));
        limiter.tryAcquire("1.1.1.1", T0);
        limiter.tryAcquire("1.1.1.1", T0);

        RateLimiter.Decision decision = limiter.tryAcquire("1.1.1.1", T0.plusSeconds(10));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.scope()).isEqualTo("por cliente");
        assertThat(decision.retryAfter()).isEqualTo(Duration.ofSeconds(50));
    }

    @Test
    void tryAcquire_clientsAreIndependent() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 1, 100, false));
        limiter.tryAcquire("1.1.1.1", T0);

        assertThat(limiter.tryAcquire("2.2.2.2", T0).allowed()).isTrue();
    }

    @Test
    void tryAcquire_afterWindowSlides_allowsAgain() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 1, 100, false));
        limiter.tryAcquire("1.1.1.1", T0);

        assertThat(limiter.tryAcquire("1.1.1.1", T0.plusSeconds(30)).allowed()).isFalse();
        assertThat(limiter.tryAcquire("1.1.1.1", T0.plusSeconds(61)).allowed()).isTrue();
    }

    @Test
    void tryAcquire_globalDailyBudgetExhausted_refusesEvenAFreshClient() {
        // The provider quota is shared, so a new IP must not get a fresh allowance.
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 100, 2, false));
        limiter.tryAcquire("1.1.1.1", T0);
        limiter.tryAcquire("2.2.2.2", T0);

        RateLimiter.Decision decision = limiter.tryAcquire("3.3.3.3", T0.plusSeconds(5));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.scope()).isEqualTo("diário global");
        assertThat(decision.retryAfter()).isCloseTo(Duration.ofHours(24), Duration.ofSeconds(10));
    }

    @Test
    void tryAcquire_refusedRequestsDoNotExtendThePenalty() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 1, 100, false));
        limiter.tryAcquire("1.1.1.1", T0);
        limiter.tryAcquire("1.1.1.1", T0.plusSeconds(30));  // refused, must not be counted

        assertThat(limiter.tryAcquire("1.1.1.1", T0.plusSeconds(61)).allowed()).isTrue();
    }

    @Test
    void tryAcquire_disabled_alwaysAllows() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(false, 1, 1, false));

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("1.1.1.1", T0).allowed()).isTrue();
        }
    }

    @Test
    void evictIdleClients_dropsClientsOutsideTheWindow() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 1, 100, false));
        limiter.tryAcquire("1.1.1.1", T0);

        limiter.evictIdleClients(T0.plusSeconds(120));

        assertThat(limiter.tryAcquire("1.1.1.1", T0.plusSeconds(120)).allowed()).isTrue();
    }
}

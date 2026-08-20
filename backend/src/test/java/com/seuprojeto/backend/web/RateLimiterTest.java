package com.seuprojeto.backend.web;

import com.seuprojeto.backend.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class RateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-08-18T12:00:00Z");

    @Test
    void construct_perClientLimitNotPositive_isRejectedNamingTheKey() {
        for (int invalid : new int[] {0, -1}) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("per-client limit of %d", invalid)
                    .isThrownBy(() -> new RateLimitProperties(true, invalid, 100, 10, 6, false))
                    .withMessageContaining("app.rate-limit.requests-per-minute-per-client");
        }
    }

    @Test
    void construct_dailyLimitNotPositive_isRejectedNamingTheKey() {
        for (int invalid : new int[] {0, -1}) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("daily limit of %d", invalid)
                    .isThrownBy(() -> new RateLimitProperties(true, 6, invalid, 10, 6, false))
                    .withMessageContaining("app.rate-limit.requests-per-day-total");
        }
    }

    @Test
    void construct_recommendLimitNotPositive_isRejectedNamingTheKey() {
        for (int invalid : new int[] {0, -1}) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("recommend limit of %d", invalid)
                    .isThrownBy(() -> new RateLimitProperties(true, 6, 100, 10, invalid, false))
                    .withMessageContaining("app.rate-limit.recommend-requests-per-minute-per-client");
        }
    }

    @Test
    void construct_authLimitNotPositive_isRejectedNamingTheKey() {
        for (int invalid : new int[] {0, -1}) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("auth limit of %d", invalid)
                    .isThrownBy(() -> new RateLimitProperties(true, 6, 100, invalid, 6, false))
                    .withMessageContaining("app.rate-limit.auth-requests-per-minute-per-client");
        }
    }

    @Test
    void tryAcquireRecommendation_hasItsOwnWindow_notTheDailyAiBudget() {
        // A recommendation costs one embedding call and no generation, so it must not be able to
        // exhaust the budget that protects generateContent.
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 6, 1, 10, 2, false));

        assertThat(limiter.tryAcquireRecommendation("10.0.0.1", T0).allowed()).isTrue();
        assertThat(limiter.tryAcquireRecommendation("10.0.0.1", T0).allowed()).isTrue();
        assertThat(limiter.tryAcquireRecommendation("10.0.0.1", T0).allowed()).isFalse();
        assertThat(limiter.tryAcquire("10.0.0.2", T0).allowed()).isTrue();
    }

    @Test
    void tryAcquireRecommendation_isSeparateFromTheAuthWindow() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 6, 100, 1, 1, false));
        limiter.tryAcquireAuthAttempt("10.0.0.1", T0);

        assertThat(limiter.tryAcquireRecommendation("10.0.0.1", T0).allowed()).isTrue();
    }

    @Test
    void tryAcquireAuthAttempt_withinTheAuthLimit_allows() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 6, 100, 3, 6, false));

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquireAuthAttempt("10.0.0.1", T0).allowed()).isTrue();
        }
        assertThat(limiter.tryAcquireAuthAttempt("10.0.0.1", T0).allowed()).isFalse();
    }

    @Test
    void tryAcquireAuthAttempt_doesNotSpendTheDailyAiBudget() {
        // The whole reason authentication has its own window: failing to log in must not be a way
        // to exhaust the day's AI quota for everybody.
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 6, 1, 10, 6, false));

        for (int i = 0; i < 5; i++) {
            limiter.tryAcquireAuthAttempt("10.0.0.1", T0);
        }

        assertThat(limiter.tryAcquire("10.0.0.2", T0).allowed()).isTrue();
    }

    @Test
    void tryAcquireAuthAttempt_isCountedPerClient() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 6, 100, 1, 6, false));

        assertThat(limiter.tryAcquireAuthAttempt("10.0.0.1", T0).allowed()).isTrue();
        assertThat(limiter.tryAcquireAuthAttempt("10.0.0.1", T0).allowed()).isFalse();
        assertThat(limiter.tryAcquireAuthAttempt("10.0.0.2", T0).allowed()).isTrue();
    }

    @Test
    void tryAcquireAuthAttempt_windowSlides() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 6, 100, 1, 6, false));
        limiter.tryAcquireAuthAttempt("10.0.0.1", T0);

        assertThat(limiter.tryAcquireAuthAttempt("10.0.0.1", T0.plus(Duration.ofSeconds(61))).allowed())
                .isTrue();
    }

    @Test
    void tryAcquire_withinPerClientLimit_allows() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 3, 100, 10, 6, false));

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("1.1.1.1", T0).allowed()).as("request %d", i).isTrue();
        }
    }

    @Test
    void tryAcquire_perClientLimitExceeded_refusesWithRetryAfter() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 2, 100, 10, 6, false));
        limiter.tryAcquire("1.1.1.1", T0);
        limiter.tryAcquire("1.1.1.1", T0);

        RateLimiter.Decision decision = limiter.tryAcquire("1.1.1.1", T0.plusSeconds(10));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.scope()).isEqualTo("por cliente");
        assertThat(decision.retryAfter()).isEqualTo(Duration.ofSeconds(50));
    }

    @Test
    void tryAcquire_clientsAreIndependent() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 1, 100, 10, 6, false));
        limiter.tryAcquire("1.1.1.1", T0);

        assertThat(limiter.tryAcquire("2.2.2.2", T0).allowed()).isTrue();
    }

    @Test
    void tryAcquire_afterWindowSlides_allowsAgain() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 1, 100, 10, 6, false));
        limiter.tryAcquire("1.1.1.1", T0);

        assertThat(limiter.tryAcquire("1.1.1.1", T0.plusSeconds(30)).allowed()).isFalse();
        assertThat(limiter.tryAcquire("1.1.1.1", T0.plusSeconds(61)).allowed()).isTrue();
    }

    @Test
    void tryAcquire_globalDailyBudgetExhausted_refusesEvenAFreshClient() {
        // The provider quota is shared, so a new IP must not get a fresh allowance.
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 100, 2, 10, 6, false));
        limiter.tryAcquire("1.1.1.1", T0);
        limiter.tryAcquire("2.2.2.2", T0);

        RateLimiter.Decision decision = limiter.tryAcquire("3.3.3.3", T0.plusSeconds(5));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.scope()).isEqualTo("diário global");
        assertThat(decision.retryAfter()).isCloseTo(Duration.ofHours(24), Duration.ofSeconds(10));
    }

    @Test
    void tryAcquire_refusedRequestsDoNotExtendThePenalty() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 1, 100, 10, 6, false));
        limiter.tryAcquire("1.1.1.1", T0);
        limiter.tryAcquire("1.1.1.1", T0.plusSeconds(30));  // refused, must not be counted

        assertThat(limiter.tryAcquire("1.1.1.1", T0.plusSeconds(61)).allowed()).isTrue();
    }

    @Test
    void tryAcquire_disabled_alwaysAllows() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(false, 1, 1, 10, 6, false));

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("1.1.1.1", T0).allowed()).isTrue();
        }
    }

    @Test
    void evictIdleClients_dropsClientsOutsideTheWindow() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties(true, 1, 100, 10, 6, false));
        limiter.tryAcquire("1.1.1.1", T0);

        limiter.evictIdleClients(T0.plusSeconds(120));

        assertThat(limiter.tryAcquire("1.1.1.1", T0.plusSeconds(120)).allowed()).isTrue();
    }
}

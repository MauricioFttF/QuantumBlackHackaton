package com.seuprojeto.backend.web;

import com.seuprojeto.backend.config.RateLimitProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window rate limiter.
 *
 * <p>Deliberately not a shared/distributed limiter: there is one backend instance, and the
 * resource being protected (a per-project AI quota) is small enough that approximate local
 * counting is sufficient. If this ever runs multi-instance, the daily budget must move to the
 * database or a cache — a per-instance counter would silently allow N times the intended cap.
 */
@Component
public class RateLimiter {

    /** Outcome of a rate-limit check. */
    public record Decision(boolean allowed, String scope, Duration retryAfter) {

        static Decision permit() {
            return new Decision(true, null, Duration.ZERO);
        }

        static Decision refuse(String scope, Duration retryAfter) {
            // Never advertise a retry shorter than a second; a client honouring it would hammer us.
            return new Decision(false, scope, retryAfter.compareTo(Duration.ofSeconds(1)) < 0
                    ? Duration.ofSeconds(1)
                    : retryAfter);
        }
    }

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration DAY = Duration.ofDays(1);

    private final RateLimitProperties properties;
    private final Map<String, Deque<Instant>> perClientHits = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> perClientAuthHits = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> perClientRecommendHits = new ConcurrentHashMap<>();
    private final Deque<Instant> globalHits = new ArrayDeque<>();

    public RateLimiter(RateLimitProperties properties) {
        this.properties = properties;
    }

    /**
     * Records an attempt and says whether it may proceed. Only allowed attempts are counted, so
     * a client being throttled cannot extend its own penalty by retrying.
     */
    public Decision tryAcquire(String clientId, Instant now) {
        if (!properties.enabled()) {
            return Decision.permit();
        }

        synchronized (this) {
            // Inside the lock: evictIdleClients could otherwise remove this deque between the
            // lookup and the addLast below, silently dropping the hit.
            Deque<Instant> clientHits = perClientHits.computeIfAbsent(clientId, key -> new ArrayDeque<>());
            evictOlderThan(clientHits, now, MINUTE);
            evictOlderThan(globalHits, now, DAY);

            if (clientHits.size() >= properties.requestsPerMinutePerClient()) {
                return Decision.refuse("por cliente",
                        remaining(clientHits.peekFirst(), now, MINUTE));
            }
            if (globalHits.size() >= properties.requestsPerDayTotal()) {
                return Decision.refuse("diário global",
                        remaining(globalHits.peekFirst(), now, DAY));
            }

            clientHits.addLast(now);
            globalHits.addLast(now);
            return Decision.permit();
        }
    }

    /**
     * Records a sign-in or registration attempt and says whether it may proceed.
     *
     * <p>Counted in its own window: authentication must not draw on the daily AI budget, or
     * failing to log in would be a way to spend someone else's quota. There is no global limit
     * here either — one bad actor must not be able to lock every user out of signing in.
     *
     * <p>This is a per-IP throttle, not account lockout: it slows a single source down, and does
     * nothing about an attack spread across many addresses. BCrypt's cost is what makes each
     * individual guess expensive.
     */
    public Decision tryAcquireAuthAttempt(String clientId, Instant now) {
        return tryAcquirePerMinute(perClientAuthHits, properties.authRequestsPerMinutePerClient(),
                "de autenticação", clientId, now);
    }

    /**
     * Records an agenda recommendation and says whether it may proceed.
     *
     * <p>Its own window for the same reason as authentication: a recommendation spends one
     * embedding call and never calls generateContent, so charging it to the daily generation budget
     * would let itinerary requests starve the chat endpoint.
     */
    public Decision tryAcquireRecommendation(String clientId, Instant now) {
        return tryAcquirePerMinute(perClientRecommendHits,
                properties.recommendRequestsPerMinutePerClient(), "de recomendações", clientId, now);
    }

    /**
     * One rolling per-client minute, in its own bucket. Shared by the windows that are not charged
     * against the daily AI budget; {@link #tryAcquire} keeps its own body because it must not record
     * a hit until the daily check has passed too.
     */
    private Decision tryAcquirePerMinute(Map<String, Deque<Instant>> hits, int limit, String scope,
                                         String clientId, Instant now) {
        if (!properties.enabled()) {
            return Decision.permit();
        }

        synchronized (this) {
            Deque<Instant> window = hits.computeIfAbsent(clientId, key -> new ArrayDeque<>());
            evictOlderThan(window, now, MINUTE);

            if (window.size() >= limit) {
                return Decision.refuse(scope, remaining(window.peekFirst(), now, MINUTE));
            }

            window.addLast(now);
            return Decision.permit();
        }
    }

    /** Frees memory for clients that have gone quiet. Called opportunistically by the filter. */
    public synchronized void evictIdleClients(Instant now) {
        evictIdleFrom(perClientHits, now);
        evictIdleFrom(perClientAuthHits, now);
        evictIdleFrom(perClientRecommendHits, now);
    }

    private static void evictIdleFrom(Map<String, Deque<Instant>> hits, Instant now) {
        hits.entrySet().removeIf(entry -> {
            evictOlderThan(entry.getValue(), now, MINUTE);
            return entry.getValue().isEmpty();
        });
    }

    private static void evictOlderThan(Deque<Instant> hits, Instant now, Duration window) {
        Instant cutoff = now.minus(window);
        while (!hits.isEmpty() && !hits.peekFirst().isAfter(cutoff)) {
            hits.pollFirst();
        }
    }

    private static Duration remaining(Instant oldestHit, Instant now, Duration window) {
        return Duration.between(now, oldestHit.plus(window));
    }
}

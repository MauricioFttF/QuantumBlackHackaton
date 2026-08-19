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

        Deque<Instant> clientHits = perClientHits.computeIfAbsent(clientId, key -> new ArrayDeque<>());
        synchronized (this) {
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

    /** Frees memory for clients that have gone quiet. Called opportunistically by the filter. */
    public synchronized void evictIdleClients(Instant now) {
        perClientHits.entrySet().removeIf(entry -> {
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

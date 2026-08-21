package com.seuprojeto.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * Tuning for {@code POST /api/agenda/recommend}. Hand-rolled validation, like the other properties
 * records here (§3.1.14 in CLAUDE.md).
 *
 * @param recommendTopK        candidate pool pulled from the agenda before conflict resolution.
 *                            Wider than {@code rag.top-k} on purpose: the planner discards
 *                            candidates that clash, so it needs more than it will return
 * @param recommendMaxDistance cosine distance above which a session is not relevant enough to
 *                            recommend. Starts equal to {@code rag.max-distance}; kept separate
 *                            because recommending an unrelated session is a worse failure than
 *                            quoting a weak chunk as context, so this one may need to tighten
 * @param defaultMaxSessions   used when the request omits {@code maxSessions}
 * @param openEndedSlotDuration how long a session with a start but no end ("12h15") is assumed to
 *                            run. The corpus has one; a zero-length assumption would make it
 *                            impossible to detect a clash with it
 * @param recommendTypes       chunk types that represent something a person can attend. Defaults to
 *                            the agenda plus its parallel sub-sessions: recommending "Sessões
 *                            Temáticas" without saying which of the three to attend is not much of a
 *                            recommendation. They share the parent's slot, so the planner picks the
 *                            best one and drops the rest as conflicts — which is the point
 * @param eventDate            the single day the corpus covers. The agenda has slots but no dates
 *                            (see {@code data/evento.json}), so this is the only thing a
 *                            {@code date} filter can be checked against
 */
@ConfigurationProperties(prefix = "agenda")
public record AgendaProperties(
        int recommendTopK,
        double recommendMaxDistance,
        int defaultMaxSessions,
        Duration openEndedSlotDuration,
        List<String> recommendTypes,
        LocalDate eventDate) {

    public AgendaProperties {
        if (recommendTopK <= 0) {
            throw new IllegalArgumentException(
                    "agenda.recommend-top-k must be positive, was " + recommendTopK);
        }
        // pgvector cosine distance is in [0, 2].
        if (recommendMaxDistance <= 0.0 || recommendMaxDistance > 2.0) {
            throw new IllegalArgumentException(
                    "agenda.recommend-max-distance must be within (0.0, 2.0], was " + recommendMaxDistance);
        }
        if (defaultMaxSessions <= 0) {
            throw new IllegalArgumentException(
                    "agenda.default-max-sessions must be positive, was " + defaultMaxSessions);
        }
        if (defaultMaxSessions > recommendTopK) {
            throw new IllegalArgumentException(
                    "agenda.default-max-sessions (%d) cannot exceed agenda.recommend-top-k (%d): the "
                            .formatted(defaultMaxSessions, recommendTopK)
                            + "candidate pool is the ceiling on what can ever be returned");
        }
        if (openEndedSlotDuration == null || openEndedSlotDuration.isNegative()
                || openEndedSlotDuration.isZero()) {
            throw new IllegalArgumentException("agenda.open-ended-slot-duration must be positive");
        }
        if (recommendTypes == null || recommendTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    "agenda.recommend-types must list at least one chunk type");
        }
        recommendTypes = List.copyOf(recommendTypes);
        if (eventDate == null) {
            throw new IllegalArgumentException("agenda.event-date must be set");
        }
    }
}

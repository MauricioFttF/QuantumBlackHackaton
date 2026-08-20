package com.seuprojeto.backend.service;

import com.seuprojeto.backend.repository.ChunkMatch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Turns ranked agenda candidates into a conflict-free itinerary, greedily: best match first, keep
 * it if it fits, stop at {@code maxSessions}.
 *
 * <p>Greedy is not optimal — a very strong match can crowd out two decent ones — but it is
 * explainable ("your best matches, minus what you cannot physically attend"), which matters more
 * here than squeezing in one extra session.
 *
 * <p>Deterministic for the same input: candidates are ordered by score, then by chunk id to break
 * ties, so two sessions with identical scores always resolve the same way.
 *
 * <p>Pure and deterministic: no Spring, no I/O, no clock.
 */
public final class ItineraryPlanner {

    private ItineraryPlanner() {
    }

    /** One accepted session and the slot it occupies. */
    public record PlannedSession(ChunkMatch match, AgendaSlot slot) {
    }

    /**
     * @param sessions       accepted sessions, ordered chronologically for display
     * @param conflictCount  candidates dropped because they clashed with something already accepted
     * @param unparsedCount  candidates whose {@code titleRef} is not a time range this code
     *                       understands. They are excluded rather than assumed conflict-free, and
     *                       counted so the caller can report the gap instead of hiding it
     */
    public record Itinerary(List<PlannedSession> sessions, int conflictCount, int unparsedCount) {

        public Itinerary {
            sessions = List.copyOf(sessions);
        }
    }

    public static Itinerary plan(List<ChunkMatch> candidates, int maxSessions,
                                 Duration openEndedDuration) {
        if (maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions must be positive, was " + maxSessions);
        }
        if (candidates == null || candidates.isEmpty()) {
            return new Itinerary(List.of(), 0, 0);
        }

        List<ChunkMatch> ranked = candidates.stream()
                .sorted(Comparator.comparingDouble(ChunkMatch::getDistance)
                        .thenComparing(ChunkMatch::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<PlannedSession> accepted = new ArrayList<>();
        int conflicts = 0;
        int unparsed = 0;

        for (ChunkMatch candidate : ranked) {
            if (accepted.size() == maxSessions) {
                break;
            }

            Optional<AgendaSlot> slot = AgendaSlot.parse(candidate.getTitleRef(), openEndedDuration);
            if (slot.isEmpty()) {
                unparsed++;
                continue;
            }

            boolean clashes = accepted.stream().anyMatch(session -> session.slot().overlaps(slot.get()));
            if (clashes) {
                conflicts++;
                continue;
            }
            accepted.add(new PlannedSession(candidate, slot.get()));
        }

        // Ranked by relevance while choosing, chronological for reading: an itinerary is something
        // you follow through the day.
        List<PlannedSession> chronological = accepted.stream()
                .sorted(Comparator.comparing(session -> session.slot().start()))
                .toList();

        return new Itinerary(chronological, conflicts, unparsed);
    }
}

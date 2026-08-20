package com.seuprojeto.backend.dto;

import com.seuprojeto.backend.repository.ChunkMatch;

/**
 * One session in a recommended itinerary.
 *
 * <p>Carries {@code id} and {@code score} in the same meaning as {@code /api/chat}'s
 * {@code sources}, so a frontend can render either with the same code.
 *
 * @param titleRef      the session's title, as ingestion stored it
 * @param startsAt      start time as {@code HH:mm}. Present because the itinerary is a schedule and
 *                      the time no longer lives in {@code titleRef} — it is parsed out of the
 *                      chunk text, so returning it saves every client from doing the same
 * @param endsAt        end time as {@code HH:mm}. For a session with no stated end this is the
 *                      assumed one ({@code agenda.open-ended-slot-duration}), which is also what
 *                      conflict detection used
 */
public record ItinerarySlot(Long id, String titleRef, String startsAt, String endsAt,
                            String content, double score) {

    public static ItinerarySlot from(ChunkMatch match, String startsAt, String endsAt) {
        return new ItinerarySlot(match.getId(), match.getTitleRef(), startsAt, endsAt,
                match.getContent(), SourceRef.similarityOf(match.getDistance()));
    }
}

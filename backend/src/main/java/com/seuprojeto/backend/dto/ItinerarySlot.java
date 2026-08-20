package com.seuprojeto.backend.dto;

import com.seuprojeto.backend.repository.ChunkMatch;

/**
 * One session in a recommended itinerary.
 *
 * <p>Carries {@code id} and {@code score} in the same meaning as {@code /api/chat}'s
 * {@code sources}, so a frontend can render either with the same code.
 */
public record ItinerarySlot(Long id, String titleRef, String content, double score) {

    public static ItinerarySlot from(ChunkMatch match) {
        return new ItinerarySlot(match.getId(), match.getTitleRef(), match.getContent(),
                SourceRef.similarityOf(match.getDistance()));
    }
}

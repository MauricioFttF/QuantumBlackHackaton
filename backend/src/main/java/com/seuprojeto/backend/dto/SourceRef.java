package com.seuprojeto.backend.dto;

import com.seuprojeto.backend.repository.ChunkMatch;

/**
 * A chunk the answer was grounded in.
 *
 * @param score similarity in [0, 1] — reads more naturally than raw cosine distance
 */
public record SourceRef(Long id, String type, String titleRef, double score) {

    public static SourceRef from(ChunkMatch match) {
        double similarity = Math.max(0.0, 1.0 - match.getDistance());
        return new SourceRef(
                match.getId(),
                match.getType(),
                match.getTitleRef(),
                Math.round(similarity * 1000.0) / 1000.0);
    }
}

package com.seuprojeto.backend.dto;

import com.seuprojeto.backend.repository.ChunkMatch;

/**
 * A chunk the answer was grounded in.
 *
 * @param score similarity in [0, 1] — reads more naturally than raw cosine distance
 */
public record SourceRef(Long id, String type, String titleRef, double score) {

    public static SourceRef from(ChunkMatch match) {
        return new SourceRef(
                match.getId(),
                match.getType(),
                match.getTitleRef(),
                similarityOf(match.getDistance()));
    }

    /**
     * Cosine distance as the similarity this API reports. Exposed so analytics stores the same
     * number the response showed — two roundings of the same value would make the dashboard
     * disagree with the answer it came from.
     */
    public static double similarityOf(double distance) {
        double similarity = Math.max(0.0, 1.0 - distance);
        return Math.round(similarity * 1000.0) / 1000.0;
    }
}

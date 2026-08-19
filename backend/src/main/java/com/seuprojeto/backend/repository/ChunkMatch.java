package com.seuprojeto.backend.repository;

/**
 * A chunk retrieved by similarity search, with its cosine distance to the query vector.
 *
 * <p>Projection rather than the entity: the 768-float embedding is never needed downstream
 * and would be dead weight on every query.
 */
public interface ChunkMatch {

    Long getId();

    String getType();

    String getTitleRef();

    String getContent();

    /** Cosine distance in [0, 2]. Lower is more similar; 0 is identical. */
    double getDistance();
}

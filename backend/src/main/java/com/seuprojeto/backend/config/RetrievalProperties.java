package com.seuprojeto.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retrieval tuning for the RAG chat endpoint.
 *
 * @param topK           how many chunks to pull from the database per question
 * @param maxDistance    cosine distance above which a chunk is treated as irrelevant. Chunks
 *                       beyond it are dropped rather than fed to the model as weak context.
 * @param maxEnumeration ceiling on chunks returned for a "list every X" question. Bounds the
 *                       prompt size if the corpus grows; a listing that hits it is truncated.
 */
@ConfigurationProperties(prefix = "rag")
public record RetrievalProperties(int topK, double maxDistance, int maxEnumeration) {

    public RetrievalProperties {
        if (topK <= 0) {
            throw new IllegalArgumentException("rag.top-k must be positive, was " + topK);
        }
        // pgvector cosine distance is in [0, 2].
        if (maxDistance <= 0.0 || maxDistance > 2.0) {
            throw new IllegalArgumentException(
                    "rag.max-distance must be within (0.0, 2.0], was " + maxDistance);
        }
        if (maxEnumeration < topK) {
            throw new IllegalArgumentException(
                    "rag.max-enumeration (%d) must be at least rag.top-k (%d)".formatted(maxEnumeration, topK));
        }
    }
}

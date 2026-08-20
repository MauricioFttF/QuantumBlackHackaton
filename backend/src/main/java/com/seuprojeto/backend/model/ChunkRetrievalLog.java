package com.seuprojeto.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One chunk, retrieved once, as context for one request.
 *
 * <p>Holds no question text and no user id by design — see the migration for why. {@code chunkId}
 * is a plain column rather than a {@code @ManyToOne}: analytics aggregates by joining explicitly,
 * and a lazy association here would mean an extra query per row on write.
 */
@Entity
@Table(name = "chunk_retrieval_log")
public class ChunkRetrievalLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chunk_id", nullable = false)
    private Long chunkId;

    @Column(name = "endpoint", nullable = false, length = 50)
    private String endpoint;

    /** Similarity, the same {@code 1 - distance} value the API reports in {@code sources}. */
    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "session_ref", length = 100)
    private String sessionRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChunkRetrievalLog() {
        // required by JPA
    }

    public ChunkRetrievalLog(Long chunkId, RetrievalEndpoint endpoint, double score,
                             String sessionRef, Instant createdAt) {
        this.chunkId = chunkId;
        this.endpoint = endpoint.storedName();
        this.score = score;
        this.sessionRef = sessionRef;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getChunkId() {
        return chunkId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public double getScore() {
        return score;
    }

    public String getSessionRef() {
        return sessionRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

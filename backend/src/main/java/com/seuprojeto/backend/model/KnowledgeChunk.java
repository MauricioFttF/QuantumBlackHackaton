package com.seuprojeto.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "knowledge_chunk")
public class KnowledgeChunk {

    /**
     * Length of the stored vector. Must be a compile-time constant for {@link Array}, so it is
     * cross-checked against {@code gemini.embedding-dimensions} at startup in IngestionService.
     */
    public static final int EMBEDDING_DIMENSIONS = 768;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String type; // "evento", "agenda", "palestrante", "artigo", "materia"

    @Column(name = "title_ref")
    private String titleRef; // nome do palestrante, título do artigo, horário da agenda, etc.

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content; // texto final que vai ser buscado pelo RAG

    /** pgvector embedding of {@link #content}, produced by EmbeddingService. */
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = EMBEDDING_DIMENSIONS)
    @Column(name = "embedding", nullable = false)
    private float[] embedding;

    /** SHA-256 of the source draft. Unique, so re-ingesting the same source is a no-op. */
    @Column(name = "content_hash", nullable = false, unique = true, length = 64)
    private String contentHash;

    protected KnowledgeChunk() {
        // required by JPA
    }

    public KnowledgeChunk(String type, String titleRef, String content, float[] embedding, String contentHash) {
        this.type = type;
        this.titleRef = titleRef;
        this.content = content;
        this.embedding = embedding;
        this.contentHash = contentHash;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitleRef() {
        return titleRef;
    }

    public void setTitleRef(String titleRef) {
        this.titleRef = titleRef;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    /** Excluded from JSON: 768 floats per row would swamp GET /api/chunks. */
    @JsonIgnore
    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }
}
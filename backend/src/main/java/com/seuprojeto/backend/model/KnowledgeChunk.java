package com.seuprojeto.backend.model;

import jakarta.persistence.*;
import com.seuprojeto.backend.util.VectorConverter;
import jakarta.persistence.Convert;

@Entity
@Table(name = "knowledge_chunk")
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String type; // "evento", "agenda", "palestrante", "artigo", "materia"

    @Column(name = "title_ref")
    private String titleRef; // nome do palestrante, título do artigo, horário da agenda, etc.

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content; // texto final que vai ser buscado pelo RAG

    @Convert(converter = VectorConverter.class)
    
    @Column(name = "embedding", columnDefinition = "vector(768)")
    private float[] embedding;

    public KnowledgeChunk() {
    }

    public KnowledgeChunk(String type, String titleRef, String content) {
        this.type = type;
        this.titleRef = titleRef;
        this.content = content;
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
    public float[] getEmbedding() {
    return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }
}
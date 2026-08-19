package com.seuprojeto.backend.repository;

import com.seuprojeto.backend.model.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    @Query(value = "SELECT * FROM knowledge_chunk " +
            "ORDER BY embedding <-> CAST(:embeddingStr AS vector) " +
            "LIMIT :k", nativeQuery = true)
    List<KnowledgeChunk> findTopSimilar(@Param("embeddingStr") String embeddingStr, @Param("k") int k);
}
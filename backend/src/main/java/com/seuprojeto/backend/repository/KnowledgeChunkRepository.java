package com.seuprojeto.backend.repository;

import com.seuprojeto.backend.model.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {
}
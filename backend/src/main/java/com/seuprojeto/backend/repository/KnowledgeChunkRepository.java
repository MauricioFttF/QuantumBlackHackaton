package com.seuprojeto.backend.repository;

import com.seuprojeto.backend.model.KnowledgeChunk;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    /** All stored content hashes, in one query — cheaper than an existsBy per chunk. */
    @Query("select c.contentHash from KnowledgeChunk c")
    List<String> findAllContentHashes();

    /**
     * Nearest chunks to {@code queryVector} by cosine distance, closest first.
     *
     * <p>{@code cosine_distance} is contributed by hibernate-vector for PostgreSQL and maps to
     * pgvector's {@code <=>} operator.
     */
    @Query("""
            select c.id as id,
                   c.type as type,
                   c.titleRef as titleRef,
                   c.content as content,
                   cosine_distance(c.embedding, :queryVector) as distance
            from KnowledgeChunk c
            order by distance
            """)
    List<ChunkMatch> findNearest(@Param("queryVector") float[] queryVector, Limit limit);

    /**
     * Same ranking, restricted to one chunk type. Used for "list every X" questions, where
     * similarity alone cannot guarantee that every X was seen.
     */
    @Query("""
            select c.id as id,
                   c.type as type,
                   c.titleRef as titleRef,
                   c.content as content,
                   cosine_distance(c.embedding, :queryVector) as distance
            from KnowledgeChunk c
            where c.type = :type
            order by distance
            """)
    List<ChunkMatch> findNearestByType(@Param("type") String type,
                                       @Param("queryVector") float[] queryVector,
                                       Limit limit);
}
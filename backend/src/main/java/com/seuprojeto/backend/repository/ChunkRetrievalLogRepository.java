package com.seuprojeto.backend.repository;

import com.seuprojeto.backend.model.ChunkRetrievalLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ChunkRetrievalLogRepository extends JpaRepository<ChunkRetrievalLog, Long> {

    /**
     * Interest per chunk type over a window.
     *
     * <p>Two near-identical queries instead of one parameterised by column, because JPQL cannot
     * parameterise {@code group by} — the grouping expression has to be written out.
     *
     * <p>The window is half-open ({@code >= from}, {@code < to}) so consecutive windows neither
     * double-count a row nor drop one.
     */
    @Query("""
            select c.type as groupKey,
                   count(l.id) as retrievalCount,
                   avg(l.score) as avgScore,
                   count(distinct l.sessionRef) as distinctSessions
            from ChunkRetrievalLog l
              join KnowledgeChunk c on c.id = l.chunkId
            where l.createdAt >= :from and l.createdAt < :to
            group by c.type
            order by count(l.id) desc, c.type asc
            """)
    List<InterestSummaryRow> summariseByType(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Interest per individual item — the view that answers "what are people asking about most".
     *
     * <p>{@code titleRef} is nullable, so a chunk without a human-readable handle groups under its
     * type rather than under an empty label.
     */
    @Query("""
            select coalesce(c.titleRef, c.type) as groupKey,
                   count(l.id) as retrievalCount,
                   avg(l.score) as avgScore,
                   count(distinct l.sessionRef) as distinctSessions
            from ChunkRetrievalLog l
              join KnowledgeChunk c on c.id = l.chunkId
            where l.createdAt >= :from and l.createdAt < :to
            group by coalesce(c.titleRef, c.type)
            order by count(l.id) desc, coalesce(c.titleRef, c.type) asc
            """)
    List<InterestSummaryRow> summariseByTitleRef(@Param("from") Instant from, @Param("to") Instant to);
}

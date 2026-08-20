package com.seuprojeto.backend.repository;

import com.seuprojeto.backend.model.ConversationTurn;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ConversationTurnRepository extends JpaRepository<ConversationTurn, Long> {

    /**
     * The newest turns of one user written after {@code after}, newest first.
     *
     * <p>The {@code after} filter is not an optimisation: it is what makes the TTL correct even
     * if the purge task never runs. An expired turn can never reach a prompt.
     *
     * <p>Tie-broken by id because both turns of one exchange are written with the same instant —
     * ordering by timestamp alone could hand back the answer before the question.
     */
    @Query("""
            select t from ConversationTurn t
            where t.userId = :userId and t.createdAt > :after
            order by t.createdAt desc, t.id desc
            """)
    List<ConversationTurn> findRecent(@Param("userId") String userId,
                                      @Param("after") Instant after,
                                      Limit limit);

    /** Drops everything at or before {@code cutoff}, for every user. Returns rows deleted. */
    @Modifying
    @Query("delete from ConversationTurn t where t.createdAt <= :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}

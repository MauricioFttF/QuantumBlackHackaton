package com.seuprojeto.backend.repository;

import com.seuprojeto.backend.model.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    /**
     * Resolves a session that is still valid at {@code now}.
     *
     * <p>The expiry is part of the query, not a check the caller may forget: an expired session
     * must be unusable the moment it expires, whether or not the purge has run.
     */
    @Query("""
            select s from UserSession s
            where s.tokenHash = :tokenHash and s.expiresAt > :now
            """)
    Optional<UserSession> findValid(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    /** Logout. Deleting by hash means one device, not every session of the account. */
    @Modifying
    @Query("delete from UserSession s where s.tokenHash = :tokenHash")
    int deleteByTokenHash(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("delete from UserSession s where s.expiresAt <= :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}

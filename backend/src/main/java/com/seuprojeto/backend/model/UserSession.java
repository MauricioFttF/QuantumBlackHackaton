package com.seuprojeto.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A login session. Holds the SHA-256 of the bearer token, never the token — the plaintext exists
 * only in the login response and in the client that keeps it.
 *
 * <p>{@code userId} is a plain column rather than a {@code @ManyToOne}: authentication needs the
 * owner's id and nothing else, and the foreign key in {@code V4__user_session.sql} already
 * guarantees the account exists.
 */
@Entity
@Table(name = "user_session")
public class UserSession {

    /** Hex-encoded SHA-256, so exactly 64 characters. */
    public static final int TOKEN_HASH_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = TOKEN_HASH_LENGTH)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected UserSession() {
        // required by JPA
    }

    public UserSession(String tokenHash, Long userId, Instant createdAt, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}

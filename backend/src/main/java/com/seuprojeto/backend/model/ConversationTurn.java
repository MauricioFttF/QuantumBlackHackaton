package com.seuprojeto.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A stored conversation turn. One row per message, keyed by user: "one chat per user" means the
 * user id is the whole conversation key.
 *
 * <p>{@code createdAt} is supplied by the caller rather than defaulted by the database, because
 * the same instant decides what is written, what is read back and what is purged — a clock the
 * tests cannot control would make the TTL untestable.
 */
@Entity
@Table(name = "conversation_turn")
public class ConversationTurn {

    /** Matches {@code user_id VARCHAR(128)}; CurrentUser rejects anything longer. */
    public static final int MAX_USER_ID_LENGTH = 128;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = MAX_USER_ID_LENGTH)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private ChatRole role;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ConversationTurn() {
        // required by JPA
    }

    public ConversationTurn(String userId, ChatRole role, String content, Instant createdAt) {
        this.userId = userId;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public ChatRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

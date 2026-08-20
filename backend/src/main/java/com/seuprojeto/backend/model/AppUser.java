package com.seuprojeto.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An account. The email is stored already normalised (see {@link EmailAddress}); the password is
 * stored only as a hash, and this class never sees or holds the plaintext.
 *
 * <p>No {@code toString} is generated or written on purpose: printing a user should not put a
 * password hash into a log line.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = EmailAddress.MAX_LENGTH)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppUser() {
        // required by JPA
    }

    public AppUser(EmailAddress email, String passwordHash, Instant createdAt) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("An account cannot be stored without a password hash");
        }
        this.email = email.value();
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

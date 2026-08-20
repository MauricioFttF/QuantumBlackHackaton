package com.seuprojeto.backend.model;

/**
 * Who produced a stored conversation turn.
 *
 * <p>Persisted by name ({@code USER} / {@code ASSISTANT}) and pinned by a CHECK constraint in
 * {@code V2__conversation_turn.sql}. Renaming a constant is a migration, not a refactor.
 */
public enum ChatRole {

    /** A question typed by the user. */
    USER,

    /** An answer this service produced, including a refusal. */
    ASSISTANT;

    /** Lowercase label used on the wire and in the prompt; {@code name()} is for the database. */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}

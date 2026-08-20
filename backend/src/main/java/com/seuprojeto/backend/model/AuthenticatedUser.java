package com.seuprojeto.backend.model;

/**
 * The identity of the caller behind one request, resolved from a bearer token.
 *
 * @param id    the account id. Also the conversation key — {@code ConversationMemory} stores
 *              history under this, so a user's chat follows the account rather than the browser
 * @param email the account's address, for showing "signed in as …"
 */
public record AuthenticatedUser(Long id, String email) {

    public AuthenticatedUser {
        if (id == null) {
            throw new IllegalArgumentException("An authenticated user needs an id");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("An authenticated user needs an email");
        }
    }

    /** The value {@code ConversationMemory} keys history by. */
    public String conversationKey() {
        return String.valueOf(id);
    }
}

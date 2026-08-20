package com.seuprojeto.backend.model;

/**
 * One turn of a conversation, as the prompt and the API see it.
 *
 * <p>Separate from the {@link ConversationTurn} entity on purpose: {@code PromptAssembler} is
 * pure and must not depend on JPA, and the API must not be able to leak a database id or a
 * timestamp it never promised.
 */
public record ConversationMessage(ChatRole role, String text) {

    public ConversationMessage {
        if (role == null) {
            throw new IllegalArgumentException("A conversation message needs a role");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A conversation message must not be blank");
        }
    }

    public static ConversationMessage of(ConversationTurn turn) {
        return new ConversationMessage(turn.getRole(), turn.getContent());
    }
}

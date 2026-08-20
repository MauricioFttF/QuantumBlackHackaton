package com.seuprojeto.backend.dto;

import com.seuprojeto.backend.model.ConversationMessage;

/**
 * One turn of stored history, as {@code GET /api/chat/history} returns it.
 *
 * @param role {@code "user"} or {@code "assistant"} — lowercase, matching what the frontend
 *             already uses for its own message list
 * @param text what was said
 */
public record ChatTurn(String role, String text) {

    public static ChatTurn from(ConversationMessage message) {
        return new ChatTurn(message.role().wireName(), message.text());
    }
}

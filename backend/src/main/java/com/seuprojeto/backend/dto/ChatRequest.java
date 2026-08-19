package com.seuprojeto.backend.dto;

/**
 * Request body of {@code POST /api/chat}.
 *
 * <p>The wire field is {@code message} (see API_CONTRACT.md). Internally it is treated as a
 * question, which is what the retrieval and prompt layers call it.
 */
public record ChatRequest(String message) {

    public ChatRequest {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("A mensagem não pode ser vazia");
        }
    }
}

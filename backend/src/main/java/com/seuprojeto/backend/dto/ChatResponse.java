package com.seuprojeto.backend.dto;

import java.util.List;

/**
 * Response body of {@code POST /api/chat}.
 *
 * @param sources the chunks the answer was grounded in; empty when nothing relevant was found
 */
public record ChatResponse(String answer, List<SourceRef> sources) {

    public ChatResponse {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}

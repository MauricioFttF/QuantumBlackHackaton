package com.seuprojeto.backend.dto.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Response body of {@code embedContent}: {@code {"embedding": {"values": [...]}}}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbedContentResponse(ContentEmbedding embedding) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContentEmbedding(List<Float> values) {
    }
}

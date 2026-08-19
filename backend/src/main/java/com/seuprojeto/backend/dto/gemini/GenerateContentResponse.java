package com.seuprojeto.backend.dto.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Response body of {@code generateContent}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerateContentResponse(List<Candidate> candidates, PromptFeedback promptFeedback) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(Content content, String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(List<Part> parts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String text) {
    }

    /** Present when the prompt itself was rejected, in which case there are no candidates. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromptFeedback(String blockReason) {
    }
}

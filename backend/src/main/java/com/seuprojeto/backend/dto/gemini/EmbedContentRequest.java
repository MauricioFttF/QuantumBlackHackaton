package com.seuprojeto.backend.dto.gemini;

import java.util.List;

/**
 * Request body for {@code POST /v1beta/models/{model}:embedContent}.
 */
public record EmbedContentRequest(String model, Content content, int outputDimensionality) {

    public record Content(List<Part> parts) {
    }

    public record Part(String text) {
    }

    public static EmbedContentRequest of(String model, String text, int outputDimensionality) {
        return new EmbedContentRequest(
                "models/" + model,
                new Content(List.of(new Part(text))),
                outputDimensionality);
    }
}

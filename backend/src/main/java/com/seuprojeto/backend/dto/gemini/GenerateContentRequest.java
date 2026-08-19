package com.seuprojeto.backend.dto.gemini;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Request body for {@code POST /v1beta/models/{model}:generateContent}.
 *
 * <p>Modelled on the wire format rather than flattened, so multimodal input slots in later:
 * an image is simply another {@link Part} carrying {@code inlineData} instead of {@code text}.
 */
public record GenerateContentRequest(
        List<Content> contents,
        Content systemInstruction,
        GenerationConfig generationConfig) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Content(String role, List<Part> parts) {

        public static Content userText(String text) {
            return new Content("user", List.of(new Part(text)));
        }

        /** System instructions carry no role. */
        public static Content systemText(String text) {
            return new Content(null, List.of(new Part(text)));
        }
    }

    public record Part(String text) {
    }

    public record GenerationConfig(double temperature, int maxOutputTokens) {
    }

    public static GenerateContentRequest of(String systemInstruction, String userPrompt,
                                            double temperature, int maxOutputTokens) {
        return new GenerateContentRequest(
                List.of(Content.userText(userPrompt)),
                Content.systemText(systemInstruction),
                new GenerationConfig(temperature, maxOutputTokens));
    }
}

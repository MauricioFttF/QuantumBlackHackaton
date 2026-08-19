package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.GeminiProperties;
import com.seuprojeto.backend.dto.gemini.GenerateContentRequest;
import com.seuprojeto.backend.dto.gemini.GenerateContentResponse;
import com.seuprojeto.backend.error.GenerationException;
import com.seuprojeto.backend.error.TransientAiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Generates text with the Gemini {@code generateContent} endpoint.
 *
 * <p>Only a candidate that finished with {@code STOP} is returned. A {@code MAX_TOKENS}
 * truncation or a safety block is an error, not a shorter answer — handing back half a
 * grounded answer as if it were complete is exactly the silent degradation this codebase
 * forbids.
 */
@Service
public class GenerationService {

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);
    private static final String FINISH_REASON_STOP = "STOP";

    private final RestClient restClient;
    private final RetryTemplate retryTemplate;
    private final String chatModel;
    private final double temperature;
    private final int maxOutputTokens;

    public GenerationService(@Qualifier("geminiChatRestClientBuilder") RestClient.Builder restClientBuilder,
                             RetryTemplate retryTemplate,
                             GeminiProperties properties) {
        this.retryTemplate = retryTemplate;
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .defaultHeader("x-goog-api-key", properties.apiKey())
                .build();
        this.chatModel = properties.chatModel();
        this.temperature = properties.chatTemperature();
        this.maxOutputTokens = properties.chatMaxOutputTokens();
    }

    /**
     * @param systemInstruction the grounding rules the model must follow
     * @param userPrompt        the question plus its retrieved context
     * @return the generated text, never blank
     * @throws GenerationException if the call fails or the answer is unusable
     */
    public String generate(String systemInstruction, String userPrompt) {
        if (systemInstruction == null || systemInstruction.isBlank()) {
            throw new IllegalArgumentException("System instruction must not be null or blank");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("User prompt must not be null or blank");
        }

        GenerateContentResponse response = callApi(systemInstruction, userPrompt);
        String text = extractText(response);

        log.debug("Generated {} chars with model {}", text.length(), chatModel);
        return text;
    }

    /** Retries transient failures; anything else fails on the first attempt. */
    private GenerateContentResponse callApi(String systemInstruction, String userPrompt) {
        try {
            return retryTemplate.execute(() -> callApiOnce(systemInstruction, userPrompt));
        } catch (RetryException e) {
            Throwable last = e.getLastException();
            // A permanent failure was already classified; do not relabel it as a retry problem.
            if (last instanceof GenerationException permanent) {
                throw permanent;
            }
            throw new GenerationException("Gemini generateContent failed after %d attempt(s) for model %s: %s"
                    .formatted(e.getRetryCount() + 1, chatModel, last == null ? "unknown cause" : last.getMessage()),
                    last);
        }
    }

    private GenerateContentResponse callApiOnce(String systemInstruction, String userPrompt) {
        try {
            return restClient.post()
                    .uri("/models/{model}:generateContent", chatModel)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(GenerateContentRequest.of(systemInstruction, userPrompt, temperature, maxOutputTokens))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
                        String body = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        int status = httpResponse.getStatusCode().value();
                        String message = "Gemini generateContent failed for model %s: HTTP %s %s"
                                .formatted(chatModel, status, body);
                        // 429 and 5xx clear up on their own; 4xx (bad key, bad request) never will.
                        throw isTransient(status) ? new TransientAiException(message)
                                : new GenerationException(message);
                    })
                    .body(GenerateContentResponse.class);
        } catch (ResourceAccessException e) {
            throw new TransientAiException(
                    "Gemini generateContent was unreachable for model " + chatModel, e);
        } catch (RestClientException e) {
            // A 2xx whose body will not parse. Not transient: retrying returns the same bytes.
            // The provider payload stays out of the message; it is already logged upstream.
            throw new GenerationException(
                    "Gemini generateContent returned an unreadable response for model " + chatModel, e);
        }
    }

    static boolean isTransient(int status) {
        return status == 429 || status >= 500;
    }

    private String extractText(GenerateContentResponse response) {
        if (response == null) {
            throw new GenerationException("Gemini generateContent returned an empty body for model " + chatModel);
        }
        if (response.candidates() == null || response.candidates().isEmpty()) {
            String blockReason = response.promptFeedback() == null
                    ? "none reported"
                    : String.valueOf(response.promptFeedback().blockReason());
            throw new GenerationException("Gemini returned no candidates for model %s (block reason: %s)"
                    .formatted(chatModel, blockReason));
        }

        GenerateContentResponse.Candidate candidate = response.candidates().getFirst();
        if (!FINISH_REASON_STOP.equals(candidate.finishReason())) {
            throw new GenerationException("Gemini stopped with finishReason %s for model %s; the answer is incomplete"
                    .formatted(candidate.finishReason(), chatModel));
        }
        if (candidate.content() == null || candidate.content().parts() == null) {
            throw new GenerationException("Gemini candidate carried no content for model " + chatModel);
        }

        String text = candidate.content().parts().stream()
                .map(GenerateContentResponse.Part::text)
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining());

        if (text.isBlank()) {
            throw new GenerationException("Gemini returned a blank answer for model " + chatModel);
        }
        return text;
    }
}

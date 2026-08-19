package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.GeminiProperties;
import com.seuprojeto.backend.dto.gemini.EmbedContentRequest;
import com.seuprojeto.backend.dto.gemini.EmbedContentResponse;
import com.seuprojeto.backend.error.EmbeddingException;
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
import java.util.List;

/**
 * Turns text into an embedding vector by calling the Gemini {@code embedContent} endpoint.
 *
 * <p>The returned vector is exactly {@code gemini.embedding-dimensions} long; a response of any
 * other size is an error, never a truncation. Failures always throw — there is no fallback
 * vector, because a zero vector would silently poison similarity search.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final RestClient restClient;
    private final RetryTemplate retryTemplate;
    private final String embeddingModel;
    private final int expectedDimensions;

    public EmbeddingService(@Qualifier("geminiRestClientBuilder") RestClient.Builder restClientBuilder,
                            RetryTemplate retryTemplate,
                            GeminiProperties properties) {
        this.retryTemplate = retryTemplate;
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .defaultHeader("x-goog-api-key", properties.apiKey())
                .build();
        this.embeddingModel = properties.embeddingModel();
        this.expectedDimensions = properties.embeddingDimensions();
    }

    /**
     * Embeds a single piece of text.
     *
     * @param text non-blank text to embed
     * @return a vector of {@code gemini.embedding-dimensions} floats
     * @throws IllegalArgumentException if {@code text} is null or blank
     * @throws EmbeddingException       if the API call fails or returns an unusable vector
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Text to embed must not be null or blank");
        }

        EmbedContentResponse response = callApi(text);
        List<Float> values = extractValues(response);

        if (values.size() != expectedDimensions) {
            throw new EmbeddingException("Embedding dimension mismatch for model %s: expected %d, got %d"
                    .formatted(embeddingModel, expectedDimensions, values.size()));
        }

        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Float value = values.get(i);
            if (value == null || !Float.isFinite(value)) {
                throw new EmbeddingException("Embedding from model %s contains a non-finite value at index %d"
                        .formatted(embeddingModel, i));
            }
            vector[i] = value;
        }

        log.debug("Embedded text of {} chars into {} dimensions using model {}",
                text.length(), vector.length, embeddingModel);
        return vector;
    }

    /** Retries transient failures; anything else fails on the first attempt. */
    private EmbedContentResponse callApi(String text) {
        try {
            return retryTemplate.execute(() -> callApiOnce(text));
        } catch (RetryException e) {
            Throwable last = e.getLastException();
            if (last instanceof EmbeddingException permanent) {
                throw permanent;
            }
            throw new EmbeddingException("Gemini embedContent failed after %d attempt(s) for model %s: %s"
                    .formatted(e.getRetryCount() + 1, embeddingModel, last == null ? "unknown cause" : last.getMessage()),
                    last);
        }
    }

    private EmbedContentResponse callApiOnce(String text) {
        try {
            return restClient.post()
                    .uri("/models/{model}:embedContent", embeddingModel)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(EmbedContentRequest.of(embeddingModel, text, expectedDimensions))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
                        String body = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        int status = httpResponse.getStatusCode().value();
                        String message = "Gemini embedContent failed for model %s: HTTP %s %s"
                                .formatted(embeddingModel, status, body);
                        throw GenerationService.isTransient(status) ? new TransientAiException(message)
                                : new EmbeddingException(message);
                    })
                    .body(EmbedContentResponse.class);
        } catch (ResourceAccessException e) {
            throw new TransientAiException(
                    "Gemini embedContent was unreachable for model " + embeddingModel, e);
        } catch (RestClientException e) {
            // A 2xx whose body will not parse. Not transient: retrying returns the same bytes.
            // The provider payload stays out of the message; it is already logged upstream.
            throw new EmbeddingException(
                    "Gemini embedContent returned an unreadable response for model " + embeddingModel, e);
        }
    }

    private List<Float> extractValues(EmbedContentResponse response) {
        if (response == null || response.embedding() == null || response.embedding().values() == null) {
            throw new EmbeddingException(
                    "Gemini embedContent returned no embedding for model " + embeddingModel);
        }
        return response.embedding().values();
    }
}

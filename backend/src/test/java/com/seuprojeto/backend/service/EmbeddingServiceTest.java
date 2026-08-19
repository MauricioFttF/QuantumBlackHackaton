package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.GeminiProperties;
import com.seuprojeto.backend.error.EmbeddingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link EmbeddingService}. The Gemini API is always stubbed — these tests
 * never touch the network and pass with no API key present.
 */
class EmbeddingServiceTest {

    private static final int DIMENSIONS = 768;
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final String MODEL = "gemini-embedding-001";
    private static final String EXPECTED_URL = BASE_URL + "/models/" + MODEL + ":embedContent";

    private MockRestServiceServer server;
    private EmbeddingService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new EmbeddingService(builder, noRetry(), properties(DIMENSIONS));
    }

    @Test
    void embed_validText_returnsVectorOfConfiguredDimension() {
        server.expect(requestTo(EXPECTED_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "fake-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value("models/" + MODEL))
                .andExpect(jsonPath("$.content.parts[0].text").value("teste"))
                .andExpect(jsonPath("$.outputDimensionality").value(DIMENSIONS))
                .andRespond(withSuccess(embeddingJson(DIMENSIONS), MediaType.APPLICATION_JSON));

        float[] vector = service.embed("teste");

        assertThat(vector).hasSize(DIMENSIONS);
        assertThat(vector[0]).isEqualTo(0.0f);
        assertThat(vector[1]).isEqualTo(0.001f);
        server.verify();
    }

    @Test
    void embed_blankText_throwsIllegalArgumentException() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.embed("   "))
                .withMessageContaining("must not be null or blank");

        server.verify(); // no HTTP call was made
    }

    @Test
    void embed_responseWithWrongDimension_throwsEmbeddingException() {
        server.expect(requestTo(EXPECTED_URL))
                .andRespond(withSuccess(embeddingJson(256), MediaType.APPLICATION_JSON));

        assertThatExceptionOfType(EmbeddingException.class)
                .isThrownBy(() -> service.embed("teste"))
                .withMessageContaining("expected 768, got 256");
    }

    @Test
    void embed_responseWithoutEmbedding_throwsEmbeddingException() {
        server.expect(requestTo(EXPECTED_URL))
                .andRespond(withSuccess("{\"usageMetadata\":{\"promptTokenCount\":3}}", MediaType.APPLICATION_JSON));

        assertThatExceptionOfType(EmbeddingException.class)
                .isThrownBy(() -> service.embed("teste"))
                .withMessageContaining("returned no embedding");
    }

    @Test
    void embed_apiReturnsServerError_throwsEmbeddingExceptionWithStatus() {
        server.expect(requestTo(EXPECTED_URL))
                .andRespond(withServerError().body("{\"error\":{\"message\":\"backend error\"}}"));

        assertThatExceptionOfType(EmbeddingException.class)
                .isThrownBy(() -> service.embed("teste"))
                .withMessageContaining("HTTP 500")
                .withMessageContaining("backend error");
    }

    @Test
    void embed_apiReturnsRateLimited_throwsEmbeddingExceptionWithStatus() {
        server.expect(requestTo(EXPECTED_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("quota exceeded"));

        assertThatExceptionOfType(EmbeddingException.class)
                .isThrownBy(() -> service.embed("teste"))
                .withMessageContaining("HTTP 429");
    }

    /** Deterministic vector: index i -> i/1000f, so assertions are exact. */
    private static String embeddingJson(int dimensions) {
        String values = IntStream.range(0, dimensions)
                .mapToObj(i -> String.valueOf(i / 1000f))
                .collect(Collectors.joining(","));
        return "{\"embedding\":{\"values\":[" + values + "]}}";
    }

    /** One attempt: these tests assert single-call behaviour, not retry behaviour. */
    static RetryTemplate noRetry() {
        return new RetryTemplate(RetryPolicy.builder().maxRetries(0).build());
    }

    private static GeminiProperties properties(int dimensions) {
        return new GeminiProperties(
                "fake-key",
                BASE_URL,
                MODEL,
                dimensions,
                "gemini-3.7-flash",
                0.2,
                1024,
                Duration.ofSeconds(5),
                Duration.ofSeconds(20),
                Duration.ofSeconds(60),
                1, Duration.ZERO, Duration.ofSeconds(30));
    }
}

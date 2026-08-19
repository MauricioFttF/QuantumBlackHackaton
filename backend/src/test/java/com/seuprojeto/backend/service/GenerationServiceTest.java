package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.GeminiProperties;
import com.seuprojeto.backend.error.GenerationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Gemini is always stubbed: no network, no API key. */
class GenerationServiceTest {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final String MODEL = "gemini-3.7-flash";
    private static final String EXPECTED_URL = BASE_URL + "/models/" + MODEL + ":generateContent";

    private MockRestServiceServer server;
    private GenerationService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new GenerationService(builder, noRetry(), properties(1));
    }

    @Test
    void generate_validPrompt_returnsAnswerAndSendsGroundingInstruction() {
        server.expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "fake-key"))
                .andExpect(jsonPath("$.systemInstruction.parts[0].text").value("Responda só com o contexto"))
                .andExpect(jsonPath("$.contents[0].role").value("user"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value("CONTEXTO: ...\nPERGUNTA: quem?"))
                .andExpect(jsonPath("$.generationConfig.temperature").value(0.2))
                .andExpect(jsonPath("$.generationConfig.maxOutputTokens").value(1024))
                .andRespond(withSuccess(candidate("Salim Ismail.", "STOP"), MediaType.APPLICATION_JSON));

        String answer = service.generate("Responda só com o contexto", "CONTEXTO: ...\nPERGUNTA: quem?");

        assertThat(answer).isEqualTo("Salim Ismail.");
        server.verify();
    }

    @Test
    void generate_multipleParts_concatenatesThem() {
        server.expect(requestTo(EXPECTED_URL)).andRespond(withSuccess(
                "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":"
                        + "[{\"text\":\"Salim \"},{\"text\":\"Ismail.\"}]}}]}",
                MediaType.APPLICATION_JSON));

        assertThat(service.generate("sys", "user")).isEqualTo("Salim Ismail.");
    }

    @Test
    void generate_truncatedAnswer_throwsRatherThanServingHalfAnAnswer() {
        server.expect(requestTo(EXPECTED_URL))
                .andRespond(withSuccess(candidate("A agenda começa às", "MAX_TOKENS"), MediaType.APPLICATION_JSON));

        assertThatExceptionOfType(GenerationException.class)
                .isThrownBy(() -> service.generate("sys", "user"))
                .withMessageContaining("MAX_TOKENS")
                .withMessageContaining("incomplete");
    }

    @Test
    void generate_safetyBlocked_throws() {
        server.expect(requestTo(EXPECTED_URL))
                .andRespond(withSuccess(candidate("", "SAFETY"), MediaType.APPLICATION_JSON));

        assertThatExceptionOfType(GenerationException.class)
                .isThrownBy(() -> service.generate("sys", "user"))
                .withMessageContaining("SAFETY");
    }

    @Test
    void generate_promptBlockedWithNoCandidates_reportsTheBlockReason() {
        server.expect(requestTo(EXPECTED_URL)).andRespond(withSuccess(
                "{\"promptFeedback\":{\"blockReason\":\"OTHER\"}}", MediaType.APPLICATION_JSON));

        assertThatExceptionOfType(GenerationException.class)
                .isThrownBy(() -> service.generate("sys", "user"))
                .withMessageContaining("no candidates")
                .withMessageContaining("OTHER");
    }

    @Test
    void generate_blankAnswer_throws() {
        server.expect(requestTo(EXPECTED_URL))
                .andRespond(withSuccess(candidate("   ", "STOP"), MediaType.APPLICATION_JSON));

        assertThatExceptionOfType(GenerationException.class)
                .isThrownBy(() -> service.generate("sys", "user"))
                .withMessageContaining("blank answer");
    }

    @Test
    void generate_rateLimited_throwsWithStatus() {
        server.expect(requestTo(EXPECTED_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("quota exceeded"));

        assertThatExceptionOfType(GenerationException.class)
                .isThrownBy(() -> service.generate("sys", "user"))
                .withMessageContaining("HTTP 429");
    }

    @Test
    void generate_serverError_throwsWithBody() {
        server.expect(requestTo(EXPECTED_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("backend error"));

        assertThatExceptionOfType(GenerationException.class)
                .isThrownBy(() -> service.generate("sys", "user"))
                .withMessageContaining("HTTP 500")
                .withMessageContaining("backend error");
    }

    @Test
    void generate_blankPrompt_throwsIllegalArgumentException() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.generate("sys", "  "));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.generate("  ", "user"));
        server.verify(); // nothing was sent
    }

    private static String candidate(String text, String finishReason) {
        return "{\"candidates\":[{\"finishReason\":\"%s\",\"content\":{\"parts\":[{\"text\":\"%s\"}]}}]}"
                .formatted(finishReason, text);
    }

    @Test
    void generate_transientServerError_retriesAndSucceeds() {
        GenerationService retrying = new GenerationService(retryingBuilder(), retryTwice(), properties(3));

        server.expect(org.springframework.test.web.client.ExpectedCount.times(2), requestTo(EXPECTED_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("high demand"));
        server.expect(requestTo(EXPECTED_URL))
                .andRespond(withSuccess(candidate("Deu certo.", "STOP"), MediaType.APPLICATION_JSON));

        assertThat(retrying.generate("sys", "user")).isEqualTo("Deu certo.");
        server.verify();
    }

    @Test
    void generate_rateLimited_isRetried() {
        GenerationService retrying = new GenerationService(retryingBuilder(), retryTwice(), properties(3));

        server.expect(requestTo(EXPECTED_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("quota"));
        server.expect(requestTo(EXPECTED_URL))
                .andRespond(withSuccess(candidate("Ok.", "STOP"), MediaType.APPLICATION_JSON));

        assertThat(retrying.generate("sys", "user")).isEqualTo("Ok.");
        server.verify();
    }

    @Test
    void generate_permanentClientError_isNotRetried() {
        GenerationService retrying = new GenerationService(retryingBuilder(), retryTwice(), properties(3));

        // A rejected API key fails identically every time; retrying only burns latency.
        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo(EXPECTED_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("API key not valid"));

        assertThatExceptionOfType(GenerationException.class)
                .isThrownBy(() -> retrying.generate("sys", "user"))
                .withMessageContaining("HTTP 403");
        server.verify();
    }

    @Test
    void generate_truncatedAnswer_isNotRetried() {
        GenerationService retrying = new GenerationService(retryingBuilder(), retryTwice(), properties(3));

        server.expect(org.springframework.test.web.client.ExpectedCount.once(), requestTo(EXPECTED_URL))
                .andRespond(withSuccess(candidate("meio", "MAX_TOKENS"), MediaType.APPLICATION_JSON));

        assertThatExceptionOfType(GenerationException.class)
                .isThrownBy(() -> retrying.generate("sys", "user"));
        server.verify();
    }

    @Test
    void generate_transientErrorOnEveryAttempt_failsWithAttemptCount() {
        GenerationService retrying = new GenerationService(retryingBuilder(), retryTwice(), properties(3));

        server.expect(org.springframework.test.web.client.ExpectedCount.times(3), requestTo(EXPECTED_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("high demand"));

        assertThatExceptionOfType(GenerationException.class)
                .isThrownBy(() -> retrying.generate("sys", "user"))
                .withMessageContaining("after 3 attempt(s)");
        server.verify();
    }

    /** Rebinds the mock server to a fresh builder so retried calls hit the same expectations. */
    private RestClient.Builder retryingBuilder() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return builder;
    }

    private static RetryTemplate retryTwice() {
        return new RetryTemplate(RetryPolicy.builder()
                .maxRetries(2)
                .delay(java.time.Duration.ofMillis(1))
                .includes(com.seuprojeto.backend.error.TransientAiException.class)
                .build());
    }

    static RetryTemplate noRetry() {
        return new RetryTemplate(RetryPolicy.builder().maxRetries(0).build());
    }

    private static GeminiProperties properties(int attempts) {
        return new GeminiProperties("fake-key", BASE_URL, "gemini-embedding-001", 768,
                MODEL, 0.2, 1024, Duration.ofSeconds(5), Duration.ofSeconds(20), Duration.ofSeconds(60),
                attempts, Duration.ZERO, Duration.ofSeconds(30));
    }
}

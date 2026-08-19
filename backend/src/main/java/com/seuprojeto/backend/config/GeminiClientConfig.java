package com.seuprojeto.backend.config;

import com.seuprojeto.backend.error.TransientAiException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class GeminiClientConfig {

    /**
     * Builder for embedding calls (sub-second). Defined here rather than relying on
     * auto-configuration so that a hung Gemini call can never block a request thread forever.
     */
    @Bean
    public RestClient.Builder geminiRestClientBuilder(GeminiProperties properties) {
        return builderWithTimeouts(properties.connectTimeout(), properties.readTimeout());
    }

    /**
     * Builder for generation calls. Separate because generateContent routinely takes several
     * seconds — sharing the embedding timeout made every chat request time out.
     */
    @Bean
    public RestClient.Builder geminiChatRestClientBuilder(GeminiProperties properties) {
        return builderWithTimeouts(properties.connectTimeout(), properties.chatReadTimeout());
    }

    /**
     * Retries only {@link TransientAiException} — 429, 5xx and timeouts. Everything else fails
     * on the first attempt, because retrying a rejected key or a safety-blocked answer just
     * burns quota and latency.
     *
     * <p>{@code retryMaxElapsed} bounds the total wall-clock time, so a chat request cannot hang
     * for attempts x readTimeout when the provider is timing out rather than erroring.
     */
    @Bean
    public RetryTemplate geminiRetryTemplate(GeminiProperties properties) {
        RetryPolicy policy = RetryPolicy.builder()
                .includes(TransientAiException.class)
                .maxRetries(properties.retryMaxAttempts() - 1L)   // policy counts retries, we configure attempts
                .delay(properties.retryInitialDelay())
                .multiplier(2.0)
                .jitter(Duration.ofMillis(250))
                .timeout(properties.retryMaxElapsed())
                .build();
        return new RetryTemplate(policy);
    }

    private static RestClient.Builder builderWithTimeouts(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder().requestFactory(requestFactory);
    }
}

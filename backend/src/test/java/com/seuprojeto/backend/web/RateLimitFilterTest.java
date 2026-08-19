package com.seuprojeto.backend.web;

import com.seuprojeto.backend.config.RateLimitProperties;
import com.seuprojeto.backend.config.WebProperties;
import com.seuprojeto.backend.controller.ChatController;
import com.seuprojeto.backend.dto.ChatResponse;
import com.seuprojeto.backend.error.GlobalExceptionHandler;
import com.seuprojeto.backend.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end behaviour of the rate limit over real HTTP, with a deliberately tiny budget. */
@WebMvcTest(ChatController.class)
@Import({GlobalExceptionHandler.class, RateLimiter.class})
@EnableConfigurationProperties({WebProperties.class, RateLimitProperties.class})
@TestPropertySource(properties = {
        "app.web.cors-allowed-origins=http://localhost:3000",
        "app.rate-limit.enabled=true",
        "app.rate-limit.requests-per-minute-per-client=2",
        "app.rate-limit.requests-per-day-total=1000",
})
class RateLimitFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @Test
    void chat_beyondPerClientLimit_returns429WithRetryAfter() throws Exception {
        when(chatService.answer(anyString())).thenReturn(new ChatResponse("ok", List.of()));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(chatRequest()).andExpect(status().isOk());
        }

        mockMvc.perform(chatRequest())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Muitas requisições"))
                .andExpect(jsonPath("$.status").value(429));

        // The blocked request must never reach the service, or the quota is spent anyway.
        verify(chatService, times(2)).answer(anyString());
    }

    @Test
    void chunks_isNotRateLimited_becauseItCostsNoAiQuota() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/chunks")).andExpect(status().isNotFound()); // no controller in slice
        }
    }

    private static org.springframework.test.web.servlet.RequestBuilder chatRequest() {
        return post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Quem fala sobre IA?\"}");
    }
}

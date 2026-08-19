package com.seuprojeto.backend.controller;

import com.seuprojeto.backend.dto.ChatResponse;
import com.seuprojeto.backend.dto.SourceRef;
import com.seuprojeto.backend.error.GenerationException;
import com.seuprojeto.backend.config.RateLimitProperties;
import com.seuprojeto.backend.config.WebProperties;
import com.seuprojeto.backend.error.GlobalExceptionHandler;
import com.seuprojeto.backend.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.seuprojeto.backend.web.RateLimiter;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@Import({GlobalExceptionHandler.class, RateLimiter.class})
@EnableConfigurationProperties({WebProperties.class, RateLimitProperties.class})
@TestPropertySource(properties = {
        "app.web.cors-allowed-origins=http://localhost:3000",
        "app.rate-limit.enabled=true",
        "app.rate-limit.requests-per-minute-per-client=100",
        "app.rate-limit.requests-per-day-total=1000",
        "app.rate-limit.trust-forwarded-header=false",
})
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @Test
    void chat_validQuestion_returns200WithAnswerAndSources() throws Exception {
        when(chatService.answer(anyString())).thenReturn(new ChatResponse(
                "Salim Ismail fala às 09h10.",
                List.of(new SourceRef(7L, "palestrante", "Salim Ismail", 0.834))));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Quem fala sobre IA?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Salim Ismail fala às 09h10."))
                .andExpect(jsonPath("$.sources[0].id").value(7))
                .andExpect(jsonPath("$.sources[0].titleRef").value("Salim Ismail"))
                .andExpect(jsonPath("$.sources[0].score").value(0.834));
    }

    @Test
    void chat_blankQuestion_returns400() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A mensagem não pode ser vazia"));
    }

    @Test
    void chat_missingMessageField_returns400() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chat_nullJsonBody_returns400NotA500() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A mensagem não pode ser vazia"));
    }

    @Test
    void chat_malformedJson_returns400WithoutJacksonInternals() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Corpo da requisição inválido ou mal formado."));
    }

    @Test
    void unknownRoute_returns404NotAGeneric500() throws Exception {
        mockMvc.perform(post("/api/nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void chat_upstreamAiFails_returns502WithoutLeakingDetails() throws Exception {
        when(chatService.answer(anyString()))
                .thenThrow(new GenerationException("Gemini generateContent failed: HTTP 500 secret-ish body"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Quem fala sobre IA?\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("Serviço de IA indisponível"))
                .andExpect(jsonPath("$.detail").value("A chamada ao provedor de IA falhou. Tente novamente em instantes."));
    }
}

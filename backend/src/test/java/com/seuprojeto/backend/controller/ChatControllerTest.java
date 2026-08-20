package com.seuprojeto.backend.controller;

import com.seuprojeto.backend.dto.ChatResponse;
import com.seuprojeto.backend.dto.SourceRef;
import com.seuprojeto.backend.error.GenerationException;
import com.seuprojeto.backend.config.RateLimitProperties;
import com.seuprojeto.backend.config.WebProperties;
import com.seuprojeto.backend.error.GlobalExceptionHandler;
import com.seuprojeto.backend.model.AuthenticatedUser;
import com.seuprojeto.backend.model.ChatRole;
import com.seuprojeto.backend.model.ConversationMessage;
import com.seuprojeto.backend.service.AuthService;
import com.seuprojeto.backend.service.ChatService;
import com.seuprojeto.backend.service.ConversationMemory;
import com.seuprojeto.backend.web.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.seuprojeto.backend.web.RateLimiter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@Import({GlobalExceptionHandler.class, RateLimiter.class, CurrentUser.class})
@EnableConfigurationProperties({WebProperties.class, RateLimitProperties.class})
@TestPropertySource(properties = {
        "app.web.cors-allowed-origins=http://localhost:3000",
        "app.rate-limit.enabled=true",
        "app.rate-limit.requests-per-minute-per-client=100",
        "app.rate-limit.requests-per-day-total=1000",
        "app.rate-limit.auth-requests-per-minute-per-client=100",
        "app.rate-limit.recommend-requests-per-minute-per-client=100",
        "app.rate-limit.trust-forwarded-header=false",
})
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private ConversationMemory conversationMemory;

    // AuthenticationFilter is a Filter bean, so it joins this slice whether or not the test cares
    // about authentication — and /api/chat is a protected path, so every request here needs a
    // token that resolves (see §3.1.13).
    @MockitoBean
    private AuthService authService;

    /** Any non-empty string: AuthService is mocked, so only the stubbing has to agree. */
    private static final String TOKEN = "session-token-for-tests";
    private static final AuthenticatedUser SIGNED_IN = new AuthenticatedUser(42L, "pedro@usp.br");

    @BeforeEach
    void signIn() {
        when(authService.authenticate(TOKEN)).thenReturn(java.util.Optional.of(SIGNED_IN));
    }

    @Test
    void chat_validQuestion_returns200WithAnswerAndSources() throws Exception {
        when(chatService.answer(any(), anyString())).thenReturn(new ChatResponse(
                "Salim Ismail fala às 09h10.",
                List.of(new SourceRef(7L, "palestrante", "Salim Ismail", 0.834))));

        mockMvc.perform(post("/api/chat").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
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
        mockMvc.perform(post("/api/chat").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A mensagem não pode ser vazia"));
    }

    @Test
    void chat_missingMessageField_returns400() throws Exception {
        mockMvc.perform(post("/api/chat").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chat_nullJsonBody_returns400NotA500() throws Exception {
        MockHttpServletRequestBuilder request = post("/api/chat").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("null");

        ResultActions response = mockMvc.perform(request);

        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A mensagem não pode ser vazia"));
    }

    @Test
    void chat_malformedJson_returns400WithoutJacksonInternals() throws Exception {
        mockMvc.perform(post("/api/chat").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
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
    void chat_authenticatedRequest_keysTheConversationByAccountId() throws Exception {
        // The conversation follows the account now, not a header the client picked.
        when(chatService.answer(any(), anyString()))
                .thenReturn(new ChatResponse("Resposta.", List.of()));

        mockMvc.perform(post("/api/chat").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Quem fala sobre IA?\"}"))
                .andExpect(status().isOk());

        verify(chatService).answer(eq("42"), eq("Quem fala sobre IA?"));
    }

    @Test
    void chat_withoutAToken_returns401AndNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Quem fala sobre IA?\"}"))
                .andExpect(status().isUnauthorized());

        verify(chatService, org.mockito.Mockito.never()).answer(any(), anyString());
    }

    @Test
    void history_returnsTheStoredConversationOldestFirst() throws Exception {
        when(conversationMemory.recall("42")).thenReturn(List.of(
                new ConversationMessage(ChatRole.USER, "Quem é Salim Ismail?"),
                new ConversationMessage(ChatRole.ASSISTANT, "Fundador da Singularity.")));

        mockMvc.perform(get("/api/chat/history").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].text").value("Quem é Salim Ismail?"))
                .andExpect(jsonPath("$[1].role").value("assistant"));
    }

    @Test
    void history_accountWithNoStoredTurns_returnsAnEmptyList() throws Exception {
        mockMvc.perform(get("/api/chat/history").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void history_withoutAToken_returns401() throws Exception {
        mockMvc.perform(get("/api/chat/history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chat_upstreamAiFails_returns502WithoutLeakingDetails() throws Exception {
        when(chatService.answer(any(), anyString()))
                .thenThrow(new GenerationException("Gemini generateContent failed: HTTP 500 secret-ish body"));

        mockMvc.perform(post("/api/chat").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Quem fala sobre IA?\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("Serviço de IA indisponível"))
                .andExpect(jsonPath("$.detail").value("A chamada ao provedor de IA falhou. Tente novamente em instantes."));
    }
}

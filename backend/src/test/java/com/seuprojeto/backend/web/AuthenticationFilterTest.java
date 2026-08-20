package com.seuprojeto.backend.web;

import com.seuprojeto.backend.config.RateLimitProperties;
import com.seuprojeto.backend.config.WebProperties;
import com.seuprojeto.backend.controller.ChatController;
import com.seuprojeto.backend.dto.ChatResponse;
import com.seuprojeto.backend.error.GlobalExceptionHandler;
import com.seuprojeto.backend.model.AuthenticatedUser;
import com.seuprojeto.backend.service.AuthService;
import com.seuprojeto.backend.service.ChatService;
import com.seuprojeto.backend.service.ConversationMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What the bearer token buys, over real HTTP. */
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
class AuthenticationFilterTest {

    private static final String TOKEN = "um-token-valido";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private ConversationMemory conversationMemory;

    @BeforeEach
    void setUp() {
        when(authService.authenticate(TOKEN))
                .thenReturn(Optional.of(new AuthenticatedUser(42L, "pedro@usp.br")));
        when(chatService.answer(any(), anyString()))
                .thenReturn(new ChatResponse("Resposta.", List.of()));
    }

    @Test
    void protectedPath_validToken_reachesTheControllerWithTheIdentity() throws Exception {
        mockMvc.perform(chatRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk());

        verify(chatService).answer(eq("42"), anyString());
    }

    @Test
    void protectedPath_noAuthorizationHeader_is401WithAChallengeAndNoServiceCall() throws Exception {
        mockMvc.perform(chatRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.title").value("Não autenticado"))
                .andExpect(jsonPath("$.status").value(401));

        // The point of doing this in a filter: nothing downstream runs, so no AI quota is spent.
        verify(chatService, never()).answer(any(), anyString());
    }

    @Test
    void protectedPath_unknownOrExpiredToken_is401() throws Exception {
        when(authService.authenticate("velho")).thenReturn(Optional.empty());

        mockMvc.perform(chatRequest().header(HttpHeaders.AUTHORIZATION, "Bearer velho"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedPath_authorizationHeaderOfAnotherScheme_is401() throws Exception {
        mockMvc.perform(chatRequest().header(HttpHeaders.AUTHORIZATION, "Basic cGVkcm86MTIzNA=="))
                .andExpect(status().isUnauthorized());

        verify(authService, never()).authenticate(anyString());
    }

    @Test
    void protectedPath_emptyBearerValue_is401WithoutAskingTheDatabase() throws Exception {
        mockMvc.perform(chatRequest().header(HttpHeaders.AUTHORIZATION, "Bearer   "))
                .andExpect(status().isUnauthorized());

        verify(authService, never()).authenticate(anyString());
    }

    @Test
    void preflight_isNotChallenged_orTheBrowserReportsACorsFailureInstead() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .options("/api/chat")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder chatRequest() {
        return post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Quem fala sobre IA?\"}");
    }
}

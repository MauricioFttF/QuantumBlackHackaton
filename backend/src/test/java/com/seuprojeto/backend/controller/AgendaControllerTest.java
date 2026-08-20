package com.seuprojeto.backend.controller;

import com.seuprojeto.backend.config.RateLimitProperties;
import com.seuprojeto.backend.config.WebProperties;
import com.seuprojeto.backend.dto.AgendaRecommendResponse;
import com.seuprojeto.backend.dto.ItinerarySlot;
import com.seuprojeto.backend.error.GlobalExceptionHandler;
import com.seuprojeto.backend.model.AuthenticatedUser;
import com.seuprojeto.backend.service.AgendaRecommendationService;
import com.seuprojeto.backend.service.AuthService;
import com.seuprojeto.backend.web.CurrentUser;
import com.seuprojeto.backend.web.RateLimiter;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgendaController.class)
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
class AgendaControllerTest {

    private static final String TOKEN = "session-token-for-tests";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgendaRecommendationService recommendationService;

    @MockitoBean
    private AuthService authService;

    @BeforeEach
    void signIn() {
        when(authService.authenticate(TOKEN))
                .thenReturn(Optional.of(new AuthenticatedUser(42L, "pedro@usp.br")));
    }

    @Test
    void recommend_authenticated_returnsTheItinerary() throws Exception {
        when(recommendationService.recommend(eq("42"), any())).thenReturn(
                AgendaRecommendResponse.of(List.of(
                        new ItinerarySlot(3L, "09h10 às 10h00", "Tecnologias Exponenciais", 0.81)),
                        15, null));

        mockMvc.perform(recommendRequest("{\"interests\":\"IA\",\"maxSessions\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itinerary[0].titleRef").value("09h10 às 10h00"))
                .andExpect(jsonPath("$.itinerary[0].score").value(0.81))
                .andExpect(jsonPath("$.consideredCount").value(15))
                .andExpect(jsonPath("$.acceptedCount").value(1));
    }

    @Test
    void recommend_withoutAToken_returns401AndNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/api/agenda/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interests\":\"IA\"}"))
                .andExpect(status().isUnauthorized());

        verify(recommendationService, never()).recommend(anyString(), any());
    }

    @Test
    void recommend_bodyUserIdMatchingTheAccount_isAccepted() throws Exception {
        when(recommendationService.recommend(eq("42"), any()))
                .thenReturn(AgendaRecommendResponse.empty(0, "sem sessões"));

        mockMvc.perform(recommendRequest("{\"userId\":\"42\",\"interests\":\"IA\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void recommend_bodyUserIdOfSomeoneElse_returns400RatherThanBeingTrustedOrIgnored() throws Exception {
        mockMvc.perform(recommendRequest("{\"userId\":\"99\",\"interests\":\"IA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("não corresponde")));

        verify(recommendationService, never()).recommend(anyString(), any());
    }

    @Test
    void recommend_noInterestsAndNoProfile_returns400WithTheReason() throws Exception {
        when(recommendationService.recommend(anyString(), any()))
                .thenThrow(new IllegalArgumentException(
                        "Descreva seus interesses no campo 'interests': esta conta ainda não tem "
                                + "histórico recente para inferir preferências."));

        mockMvc.perform(recommendRequest("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("interests")));
    }

    @Test
    void recommend_nonPositiveMaxSessions_returns400() throws Exception {
        mockMvc.perform(recommendRequest("{\"interests\":\"IA\",\"maxSessions\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("maxSessions")));
    }

    @Test
    void recommend_nullBody_returns400NotA500() throws Exception {
        mockMvc.perform(recommendRequest("null"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recommend_dateFilter_isParsedAsALocalDate() throws Exception {
        when(recommendationService.recommend(anyString(), any()))
                .thenReturn(AgendaRecommendResponse.empty(0, "outro dia"));

        mockMvc.perform(recommendRequest("{\"interests\":\"IA\",\"date\":\"2026-09-10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("outro dia"));
    }

    private static MockHttpServletRequestBuilder recommendRequest(String body) {
        return post("/api/agenda/recommend")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}

package com.seuprojeto.backend.controller;

import com.seuprojeto.backend.config.RateLimitProperties;
import com.seuprojeto.backend.config.WebProperties;
import com.seuprojeto.backend.dto.InterestSummaryEntry;
import com.seuprojeto.backend.dto.InterestSummaryResponse;
import com.seuprojeto.backend.error.GlobalExceptionHandler;
import com.seuprojeto.backend.model.InterestGrouping;
import com.seuprojeto.backend.service.AuthService;
import com.seuprojeto.backend.service.InterestAnalyticsService;
import com.seuprojeto.backend.web.CurrentUser;
import com.seuprojeto.backend.web.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalyticsController.class)
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
class AnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InterestAnalyticsService analyticsService;

    @MockitoBean
    private AuthService authService;

    @Test
    void interestSummary_withoutAnyToken_isAllowed_becauseTheDataIsAggregateOnly() throws Exception {
        // Documented as an open question rather than a decision (see AnalyticsController): the
        // endpoint exposes counts, never question text or per-user rows. This test pins today's
        // behaviour so closing it later is a visible change.
        when(analyticsService.summarise(any(), any(), any())).thenReturn(summary());

        mockMvc.perform(get("/api/analytics/interest-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].key").value("Salim Ismail"))
                .andExpect(jsonPath("$.results[0].retrievalCount").value(42))
                .andExpect(jsonPath("$.results[0].avgScore").value(0.784))
                .andExpect(jsonPath("$.results[0].distinctSessions").value(19))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void interestSummary_defaultsToTitleRef_theViewOrganizersWant() throws Exception {
        when(analyticsService.summarise(any(), any(), any())).thenReturn(summary());

        mockMvc.perform(get("/api/analytics/interest-summary")).andExpect(status().isOk());

        verify(analyticsService).summarise(eq(null), eq(null), eq(InterestGrouping.TITLE_REF));
    }

    @Test
    void interestSummary_groupByType_isPassedThrough() throws Exception {
        when(analyticsService.summarise(any(), any(), any())).thenReturn(summary());

        mockMvc.perform(get("/api/analytics/interest-summary").param("groupBy", "type"))
                .andExpect(status().isOk());

        verify(analyticsService).summarise(eq(null), eq(null), eq(InterestGrouping.TYPE));
    }

    @Test
    void interestSummary_windowIsParsedAsInstants() throws Exception {
        when(analyticsService.summarise(any(), any(), any())).thenReturn(summary());

        mockMvc.perform(get("/api/analytics/interest-summary")
                        .param("from", "2026-08-19T00:00:00Z")
                        .param("to", "2026-08-19T23:59:59Z"))
                .andExpect(status().isOk());

        verify(analyticsService).summarise(
                eq(Instant.parse("2026-08-19T00:00:00Z")),
                eq(Instant.parse("2026-08-19T23:59:59Z")),
                any());
    }

    @Test
    void interestSummary_unknownGroupBy_returns400ListingWhatIsAccepted() throws Exception {
        mockMvc.perform(get("/api/analytics/interest-summary").param("groupBy", "speaker"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("titleRef")));
    }

    @Test
    void interestSummary_invertedWindow_returns400() throws Exception {
        when(analyticsService.summarise(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("'from' deve ser anterior a 'to'"));

        mockMvc.perform(get("/api/analytics/interest-summary")
                        .param("from", "2026-08-19T23:00:00Z")
                        .param("to", "2026-08-19T01:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    private static InterestSummaryResponse summary() {
        return new InterestSummaryResponse(
                Instant.parse("2026-08-19T00:00:00Z"),
                Instant.parse("2026-08-19T23:59:59Z"),
                "titleRef",
                List.of(new InterestSummaryEntry("Salim Ismail", 42, 0.784, 19)),
                false);
    }
}

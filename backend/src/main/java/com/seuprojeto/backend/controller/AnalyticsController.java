package com.seuprojeto.backend.controller;

import com.seuprojeto.backend.dto.InterestSummaryResponse;
import com.seuprojeto.backend.model.InterestGrouping;
import com.seuprojeto.backend.service.InterestAnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The organizer dashboard's data source.
 *
 * <p><b>Open question, deliberately not decided here:</b> this endpoint has no authentication. It
 * exposes aggregate counts only — no question text, no user or session identity, nothing that
 * reconstructs an individual's behaviour — so leaving it open is defensible for a demo. What it
 * does leak is a business signal (which sessions and speakers are drawing attention), which an
 * organizer may not want public before the event. Requiring a token is a one-line change: add the
 * path to {@code AuthenticationFilter.PROTECTED_PATHS}. Flagged in CLAUDE.md §4 for a decision.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final InterestAnalyticsService analyticsService;

    public AnalyticsController(InterestAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * @param from     ISO-8601 instant, inclusive. Defaults to {@code to - analytics.default-window}
     * @param to       ISO-8601 instant, exclusive. Defaults to now
     * @param groupBy  {@code type} or {@code titleRef}; defaults to {@code titleRef}, the view that
     *                 answers "what are people asking about most"
     */
    @GetMapping("/interest-summary")
    public InterestSummaryResponse interestSummary(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false, defaultValue = "titleRef") String groupBy) {
        return analyticsService.summarise(from, to, InterestGrouping.fromWire(groupBy));
    }
}

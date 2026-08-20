package com.seuprojeto.backend.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response of {@code GET /api/analytics/interest-summary}.
 *
 * <p>The window is echoed back because both bounds have defaults — a client that sent neither still
 * needs to know what period it is looking at.
 *
 * @param truncated true when {@code analytics.max-results} cut the tail off, so a reader knows the
 *                  list is the top N and not everything
 */
public record InterestSummaryResponse(Instant from, Instant to, String groupBy,
                                      List<InterestSummaryEntry> results, boolean truncated) {

    public InterestSummaryResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }
}

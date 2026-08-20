package com.seuprojeto.backend.dto;

import com.seuprojeto.backend.repository.InterestSummaryRow;

/**
 * One line of the organizer dashboard.
 *
 * @param key              the chunk type or the item's title, depending on {@code groupBy}
 * @param retrievalCount   how many times this was sent as context
 * @param avgScore         mean similarity, rounded like every other score this API reports
 * @param distinctSessions how many separate requests it was retrieved by. With one opaque id per
 *                         request, this counts requests — not people, which this table cannot know
 */
public record InterestSummaryEntry(String key, long retrievalCount, double avgScore,
                                   long distinctSessions) {

    public static InterestSummaryEntry from(InterestSummaryRow row) {
        return new InterestSummaryEntry(
                row.getGroupKey(),
                row.getRetrievalCount(),
                Math.round(row.getAvgScore() * 1000.0) / 1000.0,
                row.getDistinctSessions());
    }
}

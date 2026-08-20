package com.seuprojeto.backend.dto;

import java.time.LocalDate;

/**
 * Request body of {@code POST /api/agenda/recommend}.
 *
 * <p>{@code userId} is accepted for compatibility with the specified shape, but the identity that
 * counts comes from the bearer token — the controller rejects a value that disagrees with the
 * authenticated account rather than trusting it or ignoring it.
 *
 * @param interests   free text. Optional only if the account has a stored profile
 * @param maxSessions how many sessions to return; omitted means {@code agenda.default-max-sessions}
 * @param date        optional day filter. The corpus is a single-day event whose agenda rows carry
 *                    no date, so this is checked against {@code agenda.event-date}
 */
public record AgendaRecommendRequest(String userId, String interests, Integer maxSessions,
                                     LocalDate date) {

    public AgendaRecommendRequest {
        if (maxSessions != null && maxSessions <= 0) {
            throw new IllegalArgumentException("O campo 'maxSessions' deve ser maior que zero");
        }
    }
}

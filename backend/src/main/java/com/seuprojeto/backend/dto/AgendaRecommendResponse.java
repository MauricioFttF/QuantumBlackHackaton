package com.seuprojeto.backend.dto;

import java.util.List;

/**
 * Response of {@code POST /api/agenda/recommend}.
 *
 * @param itinerary       accepted sessions, chronological. Empty is a valid answer, never padded
 *                        with filler to reach {@code maxSessions}
 * @param consideredCount candidates the planner actually weighed — the agenda sessions that cleared
 *                        {@code agenda.recommend-max-distance}, not the raw pool it asked for
 * @param acceptedCount   {@code itinerary.size()}, stated explicitly so a client can compare it
 *                        with {@code consideredCount} without counting
 * @param message         why the itinerary looks the way it does when that is not obvious: nothing
 *                        relevant found, sessions dropped for clashing, a date that is not the
 *                        event's. Null when there is nothing to explain
 */
public record AgendaRecommendResponse(List<ItinerarySlot> itinerary, int consideredCount,
                                      int acceptedCount, String message) {

    public AgendaRecommendResponse {
        itinerary = itinerary == null ? List.of() : List.copyOf(itinerary);
    }

    public static AgendaRecommendResponse of(List<ItinerarySlot> itinerary, int consideredCount,
                                             String message) {
        return new AgendaRecommendResponse(itinerary, consideredCount, itinerary.size(), message);
    }

    public static AgendaRecommendResponse empty(int consideredCount, String message) {
        return new AgendaRecommendResponse(List.of(), consideredCount, 0, message);
    }
}

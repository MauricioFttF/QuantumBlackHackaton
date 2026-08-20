package com.seuprojeto.backend.service;

import com.seuprojeto.backend.repository.ChunkMatch;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.seuprojeto.backend.service.PromptAssemblerTest.match;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ItineraryPlannerTest {

    private static final Duration OPEN_ENDED = Duration.ofMinutes(45);

    @Test
    void plan_noConflicts_takesTheBestMatchesUpToTheLimit() {
        List<ChunkMatch> candidates = List.of(
                match(1L, "agenda", "Tecnologias Exponenciais", "Agenda do evento — Horário: 09:10 às 10:00 — Tecnologias Exponenciais", 0.18),
                match(2L, "agenda", "Inovação no Brasil", "Agenda do evento — Horário: 10:35 às 11:15 — Inovação no Brasil", 0.25),
                match(3L, "agenda", "Sessões Temáticas", "Agenda do evento — Horário: 11:30 às 12:15 — Sessões Temáticas", 0.31));

        ItineraryPlanner.Itinerary itinerary = ItineraryPlanner.plan(candidates, 2, OPEN_ENDED);

        assertThat(itinerary.sessions()).extracting(session -> session.match().getId())
                .containsExactly(1L, 2L);
        assertThat(itinerary.conflictCount()).isZero();
        assertThat(itinerary.unparsedCount()).isZero();
    }

    @Test
    void plan_conflictingSlot_keepsTheBetterMatchAndCountsTheDrop() {
        List<ChunkMatch> candidates = List.of(
                match(1L, "agenda", "melhor", "Agenda do evento — Horário: 09:10 às 10:00 — melhor", 0.18),
                match(2L, "agenda", "conflita", "Agenda do evento — Horário: 09:30 às 10:30 — conflita", 0.22));

        ItineraryPlanner.Itinerary itinerary = ItineraryPlanner.plan(candidates, 5, OPEN_ENDED);

        assertThat(itinerary.sessions()).hasSize(1);
        assertThat(itinerary.sessions().getFirst().match().getId()).isEqualTo(1L);
        assertThat(itinerary.conflictCount()).isEqualTo(1);
    }

    @Test
    void plan_everythingConflicts_returnsWhatFitsWithoutPaddingToTheLimit() {
        // The spec's edge case: interests all point at one slot. One session is the honest answer.
        List<ChunkMatch> candidates = List.of(
                match(1L, "agenda", "a", "Agenda do evento — Horário: 09:10 às 10:00 — a", 0.18),
                match(2L, "agenda", "b", "Agenda do evento — Horário: 09:15 às 10:00 — b", 0.19),
                match(3L, "agenda", "c", "Agenda do evento — Horário: 09:20 às 09:50 — c", 0.20));

        ItineraryPlanner.Itinerary itinerary = ItineraryPlanner.plan(candidates, 5, OPEN_ENDED);

        assertThat(itinerary.sessions()).hasSize(1);
        assertThat(itinerary.conflictCount()).isEqualTo(2);
    }

    @Test
    void plan_ordersOutputChronologically_whileChoosingByScore() {
        List<ChunkMatch> candidates = List.of(
                match(1L, "agenda", "melhor mas mais tarde", "Agenda do evento — Horário: 11:30 às 12:15 — melhor mas mais tarde", 0.10),
                match(2L, "agenda", "pior mas mais cedo", "Agenda do evento — Horário: 09:10 às 10:00 — pior mas mais cedo", 0.40));

        ItineraryPlanner.Itinerary itinerary = ItineraryPlanner.plan(candidates, 5, OPEN_ENDED);

        // Ordered by clock time, not by score: the better match is the later session.
        assertThat(itinerary.sessions()).extracting(session -> session.slot().startsAt())
                .containsExactly("09:10", "11:30");
    }

    @Test
    void plan_unparseableSlot_isExcludedAndCountedNotTreatedAsConflictFree() {
        List<ChunkMatch> candidates = List.of(
                match(1L, "agenda", "horário a definir", "sem horário", 0.10),
                match(2L, "agenda", "com horário", "Agenda do evento — Horário: 09:10 às 10:00 — com horário", 0.20));

        ItineraryPlanner.Itinerary itinerary = ItineraryPlanner.plan(candidates, 5, OPEN_ENDED);

        assertThat(itinerary.sessions()).hasSize(1);
        assertThat(itinerary.sessions().getFirst().match().getId()).isEqualTo(2L);
        assertThat(itinerary.unparsedCount()).isEqualTo(1);
    }

    @Test
    void plan_tiedScores_resolvesByChunkIdSoTheResultIsDeterministic() {
        List<ChunkMatch> ordering = List.of(
                match(9L, "agenda", "a", "Agenda do evento — Horário: 09:10 às 10:00 — a", 0.20),
                match(4L, "agenda", "b", "Agenda do evento — Horário: 10:00 às 10:35 — b", 0.20));
        List<ChunkMatch> reversed = List.of(ordering.get(1), ordering.get(0));

        assertThat(ItineraryPlanner.plan(ordering, 1, OPEN_ENDED).sessions().getFirst().match().getId())
                .isEqualTo(4L)
                .isEqualTo(ItineraryPlanner.plan(reversed, 1, OPEN_ENDED)
                        .sessions().getFirst().match().getId());
    }

    @Test
    void plan_noCandidates_isAnEmptyItinerary() {
        ItineraryPlanner.Itinerary itinerary = ItineraryPlanner.plan(List.of(), 5, OPEN_ENDED);

        assertThat(itinerary.sessions()).isEmpty();
        assertThat(itinerary.conflictCount()).isZero();
    }

    @Test
    void plan_nonPositiveMaxSessions_throws() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ItineraryPlanner.plan(List.of(), 0, OPEN_ENDED));
    }
}

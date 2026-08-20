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
                match(1L, "agenda", "09h10 às 10h00", "Tecnologias Exponenciais", 0.18),
                match(2L, "agenda", "10h35 às 11h15", "Inovação no Brasil", 0.25),
                match(3L, "agenda", "11h30 às 12h15", "Sessões Temáticas", 0.31));

        ItineraryPlanner.Itinerary itinerary = ItineraryPlanner.plan(candidates, 2, OPEN_ENDED);

        assertThat(itinerary.sessions()).extracting(session -> session.match().getId())
                .containsExactly(1L, 2L);
        assertThat(itinerary.conflictCount()).isZero();
        assertThat(itinerary.unparsedCount()).isZero();
    }

    @Test
    void plan_conflictingSlot_keepsTheBetterMatchAndCountsTheDrop() {
        List<ChunkMatch> candidates = List.of(
                match(1L, "agenda", "09h10 às 10h00", "melhor", 0.18),
                match(2L, "agenda", "09h30 às 10h30", "conflita", 0.22));

        ItineraryPlanner.Itinerary itinerary = ItineraryPlanner.plan(candidates, 5, OPEN_ENDED);

        assertThat(itinerary.sessions()).hasSize(1);
        assertThat(itinerary.sessions().getFirst().match().getId()).isEqualTo(1L);
        assertThat(itinerary.conflictCount()).isEqualTo(1);
    }

    @Test
    void plan_everythingConflicts_returnsWhatFitsWithoutPaddingToTheLimit() {
        // The spec's edge case: interests all point at one slot. One session is the honest answer.
        List<ChunkMatch> candidates = List.of(
                match(1L, "agenda", "09h10 às 10h00", "a", 0.18),
                match(2L, "agenda", "09h15 às 10h00", "b", 0.19),
                match(3L, "agenda", "09h20 às 09h50", "c", 0.20));

        ItineraryPlanner.Itinerary itinerary = ItineraryPlanner.plan(candidates, 5, OPEN_ENDED);

        assertThat(itinerary.sessions()).hasSize(1);
        assertThat(itinerary.conflictCount()).isEqualTo(2);
    }

    @Test
    void plan_ordersOutputChronologically_whileChoosingByScore() {
        List<ChunkMatch> candidates = List.of(
                match(1L, "agenda", "11h30 às 12h15", "melhor mas mais tarde", 0.10),
                match(2L, "agenda", "09h10 às 10h00", "pior mas mais cedo", 0.40));

        ItineraryPlanner.Itinerary itinerary = ItineraryPlanner.plan(candidates, 5, OPEN_ENDED);

        assertThat(itinerary.sessions()).extracting(session -> session.match().getTitleRef())
                .containsExactly("09h10 às 10h00", "11h30 às 12h15");
    }

    @Test
    void plan_unparseableSlot_isExcludedAndCountedNotTreatedAsConflictFree() {
        List<ChunkMatch> candidates = List.of(
                match(1L, "agenda", "horário a definir", "sem horário", 0.10),
                match(2L, "agenda", "09h10 às 10h00", "com horário", 0.20));

        ItineraryPlanner.Itinerary itinerary = ItineraryPlanner.plan(candidates, 5, OPEN_ENDED);

        assertThat(itinerary.sessions()).hasSize(1);
        assertThat(itinerary.sessions().getFirst().match().getId()).isEqualTo(2L);
        assertThat(itinerary.unparsedCount()).isEqualTo(1);
    }

    @Test
    void plan_tiedScores_resolvesByChunkIdSoTheResultIsDeterministic() {
        List<ChunkMatch> ordering = List.of(
                match(9L, "agenda", "09h10 às 10h00", "a", 0.20),
                match(4L, "agenda", "10h00 às 10h35", "b", 0.20));
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

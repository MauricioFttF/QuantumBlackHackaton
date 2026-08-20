package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.AgendaProperties;
import com.seuprojeto.backend.dto.AgendaRecommendRequest;
import com.seuprojeto.backend.dto.AgendaRecommendResponse;
import com.seuprojeto.backend.error.EmbeddingException;
import com.seuprojeto.backend.model.RetrievalEndpoint;
import com.seuprojeto.backend.repository.ChunkMatch;
import com.seuprojeto.backend.repository.KnowledgeChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.seuprojeto.backend.service.PromptAssemblerTest.match;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgendaRecommendationServiceTest {

    private static final String USER = "42";
    private static final LocalDate EVENT_DATE = LocalDate.of(2026, 8, 26);
    private static final AgendaProperties CONFIG = new AgendaProperties(
            15, 0.8, 5, Duration.ofMinutes(45), EVENT_DATE);

    private EmbeddingService embeddingService;
    private KnowledgeChunkRepository repository;
    private RetrievalLogger retrievalLogger;
    private AgendaRecommendationService service;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        repository = mock(KnowledgeChunkRepository.class);
        retrievalLogger = mock(RetrievalLogger.class);
        when(embeddingService.embed(anyString())).thenReturn(new float[768]);
        // No stored profile: the port is a single-method interface, so a test supplies one inline.
        service = serviceWithProfile(userId -> Optional.empty());
    }

    private AgendaRecommendationService serviceWithProfile(InterestProfilePort profile) {
        return new AgendaRecommendationService(embeddingService, repository, profile,
                retrievalLogger, CONFIG);
    }

    private void agendaContains(ChunkMatch... matches) {
        when(repository.findNearestByType(eq("agenda"), any(), any())).thenReturn(List.of(matches));
    }

    @Test
    void recommend_interestsOnly_embedsTheStatedTextAndReturnsAnItinerary() {
        agendaContains(match(3L, "agenda", "09h10 às 10h00", "Tecnologias Exponenciais", 0.19));

        AgendaRecommendResponse response = service.recommend(USER,
                new AgendaRecommendRequest(null, "inteligência artificial em operações", 5, null));

        verify(embeddingService).embed("inteligência artificial em operações");
        assertThat(response.itinerary()).hasSize(1);
        assertThat(response.itinerary().getFirst().titleRef()).isEqualTo("09h10 às 10h00");
        assertThat(response.itinerary().getFirst().score()).isEqualTo(0.81);
        assertThat(response.acceptedCount()).isEqualTo(1);
        assertThat(response.consideredCount()).isEqualTo(1);
    }

    @Test
    void recommend_storedProfileOnly_usesIt() {
        service = serviceWithProfile(userId -> Optional.of("agentes de IA em serviços financeiros"));
        agendaContains(match(7L, "agenda", "11h30 às 12h15", "Sessões Temáticas", 0.22));

        service.recommend(USER, new AgendaRecommendRequest(null, null, 5, null));

        verify(embeddingService).embed("agentes de IA em serviços financeiros");
    }

    @Test
    void recommend_profileAndInterests_concatenatesWithTheStatedTextFirst() {
        // Concatenated, not averaged: averaging two embeddings lands somewhere that may match
        // neither, and silently. Stated text leads so it dominates when the two disagree.
        service = serviceWithProfile(userId -> Optional.of("perfil antigo"));
        agendaContains(match(3L, "agenda", "09h10 às 10h00", "tema", 0.2));

        service.recommend(USER, new AgendaRecommendRequest(null, "quero falar de varejo", 5, null));

        verify(embeddingService).embed("quero falar de varejo perfil antigo");
    }

    @Test
    void recommend_noInterestsAndNoProfile_throwsInsteadOfEmbeddingNothing() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.recommend(USER, new AgendaRecommendRequest(null, "  ", 5, null)))
                .withMessageContaining("interests");

        verify(embeddingService, never()).embed(anyString());
    }

    @Test
    void recommend_anonymousCallerWithNoInterests_throws() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.recommend(null, new AgendaRecommendRequest(null, null, 5, null)));
    }

    @Test
    void recommend_nothingClearsTheDistanceThreshold_is200WithAnEmptyItineraryAndAReason() {
        agendaContains(match(3L, "agenda", "09h10 às 10h00", "tema", 0.95));

        AgendaRecommendResponse response = service.recommend(USER,
                new AgendaRecommendRequest(null, "capital da Mongólia", 5, null));

        assertThat(response.itinerary()).isEmpty();
        assertThat(response.acceptedCount()).isZero();
        assertThat(response.message()).contains("Nenhuma sessão");
        verify(retrievalLogger, never()).record(any(), any());
    }

    @Test
    void recommend_dateThatIsNotTheEventDay_isEmptyWithTheEventDateInTheMessage() {
        AgendaRecommendResponse response = service.recommend(USER,
                new AgendaRecommendRequest(null, "IA", 5, LocalDate.of(2026, 9, 10)));

        assertThat(response.itinerary()).isEmpty();
        assertThat(response.message()).contains("2026-08-26").contains("2026-09-10");
        verify(embeddingService, never()).embed(anyString());
    }

    @Test
    void recommend_dateThatIsTheEventDay_proceeds() {
        agendaContains(match(3L, "agenda", "09h10 às 10h00", "tema", 0.2));

        AgendaRecommendResponse response = service.recommend(USER,
                new AgendaRecommendRequest(null, "IA", 5, EVENT_DATE));

        assertThat(response.itinerary()).hasSize(1);
    }

    @Test
    void recommend_asksForAWiderPoolThanItReturns_soConflictsCanBeResolved() {
        agendaContains(match(3L, "agenda", "09h10 às 10h00", "tema", 0.2));

        service.recommend(USER, new AgendaRecommendRequest(null, "IA", 2, null));

        verify(repository).findNearestByType(eq("agenda"), any(), eq(Limit.of(15)));
    }

    @Test
    void recommend_omittedMaxSessions_fallsBackToTheConfiguredDefault() {
        agendaContains(
                match(1L, "agenda", "08h15 às 09h00", "a", 0.10),
                match(2L, "agenda", "09h00 às 09h10", "b", 0.11),
                match(3L, "agenda", "09h10 às 10h00", "c", 0.12),
                match(4L, "agenda", "10h00 às 10h35", "d", 0.13),
                match(5L, "agenda", "10h35 às 11h15", "e", 0.14),
                match(6L, "agenda", "11h15 às 11h30", "f", 0.15));

        AgendaRecommendResponse response = service.recommend(USER,
                new AgendaRecommendRequest(null, "tudo", null, null));

        assertThat(response.acceptedCount()).isEqualTo(5);
    }

    @Test
    void recommend_fewerSessionsThanAskedBecauseOfConflicts_explainsWhy() {
        agendaContains(
                match(1L, "agenda", "09h10 às 10h00", "a", 0.10),
                match(2L, "agenda", "09h30 às 10h30", "b", 0.11));

        AgendaRecommendResponse response = service.recommend(USER,
                new AgendaRecommendRequest(null, "IA", 5, null));

        assertThat(response.acceptedCount()).isEqualTo(1);
        assertThat(response.message()).contains("conflitavam");
    }

    @Test
    void recommend_recordsAnalyticsForWhatWasRecommended_notForCandidatesNobodySaw() {
        agendaContains(
                match(1L, "agenda", "09h10 às 10h00", "aceita", 0.10),
                match(2L, "agenda", "09h30 às 10h30", "descartada por conflito", 0.11));

        service.recommend(USER, new AgendaRecommendRequest(null, "IA", 5, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChunkMatch>> logged = ArgumentCaptor.forClass(List.class);
        verify(retrievalLogger).record(eq(RetrievalEndpoint.AGENDA_RECOMMEND), logged.capture());
        assertThat(logged.getValue()).extracting(ChunkMatch::getId).containsExactly(1L);
    }

    @Test
    void recommend_embeddingFails_propagatesInsteadOfReturningAnEmptyItinerary() {
        when(embeddingService.embed(anyString())).thenThrow(new EmbeddingException("Gemini is down"));

        assertThatExceptionOfType(EmbeddingException.class)
                .isThrownBy(() -> service.recommend(USER, new AgendaRecommendRequest(null, "IA", 5, null)));

        verify(retrievalLogger, never()).record(any(), any());
    }
}

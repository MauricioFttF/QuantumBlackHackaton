package com.seuprojeto.backend.service;

import com.seuprojeto.backend.model.ChunkRetrievalLog;
import com.seuprojeto.backend.model.RetrievalEndpoint;
import com.seuprojeto.backend.repository.ChunkRetrievalLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.seuprojeto.backend.service.PromptAssemblerTest.match;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RetrievalLoggerTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    private ChunkRetrievalLogRepository repository;
    private RetrievalLogger logger;

    @BeforeEach
    void setUp() {
        repository = mock(ChunkRetrievalLogRepository.class);
        logger = new RetrievalLogger(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @SuppressWarnings("unchecked")
    void record_writesOneRowPerChunk_withTheSameScoreTheApiReports() {
        ArgumentCaptor<List<ChunkRetrievalLog>> saved = ArgumentCaptor.forClass(List.class);

        logger.record(RetrievalEndpoint.CHAT, List.of(
                match(7L, "palestrante", "Salim Ismail", "bio", 0.166),
                match(3L, "agenda", "09h10 às 10h00", "tema", 0.30)));

        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(
                        ChunkRetrievalLog::getChunkId, ChunkRetrievalLog::getEndpoint,
                        ChunkRetrievalLog::getScore, ChunkRetrievalLog::getCreatedAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(7L, "chat", 0.834, NOW),
                        org.assertj.core.groups.Tuple.tuple(3L, "chat", 0.7, NOW));
    }

    @Test
    @SuppressWarnings("unchecked")
    void record_allRowsOfOneRequestShareOneOpaqueSessionRef() {
        ArgumentCaptor<List<ChunkRetrievalLog>> saved = ArgumentCaptor.forClass(List.class);

        logger.record(RetrievalEndpoint.AGENDA_RECOMMEND, List.of(
                match(1L, "agenda", "09h10 às 10h00", "a", 0.2),
                match(2L, "agenda", "10h00 às 10h35", "b", 0.3)));

        verify(repository).saveAll(saved.capture());
        List<ChunkRetrievalLog> rows = saved.getValue();
        assertThat(rows).extracting(ChunkRetrievalLog::getSessionRef).containsOnly(
                rows.getFirst().getSessionRef());
        assertThat(rows.getFirst().getSessionRef()).isNotBlank();
        assertThat(rows.getFirst().getEndpoint()).isEqualTo("agenda_recommend");
    }

    @Test
    void record_noChunks_writesNothing_becauseARefusalRetrievedNoContext() {
        logger.record(RetrievalEndpoint.CHAT, List.of());
        logger.record(RetrievalEndpoint.CHAT, null);

        verify(repository, never()).saveAll(any());
    }

    @Test
    void record_databaseFailure_isSwallowed_soAnalyticsCannotBreakTheParentRequest() {
        // The one deliberate exception to "fail loudly" in this codebase: the user's answer is
        // already computed and paid for, and a dashboard row is not worth losing it over.
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("connection closed"))
                .when(repository).saveAll(any());

        assertThatCode(() -> logger.record(RetrievalEndpoint.CHAT,
                List.of(match(7L, "palestrante", "Salim Ismail", "bio", 0.166))))
                .doesNotThrowAnyException();
    }
}

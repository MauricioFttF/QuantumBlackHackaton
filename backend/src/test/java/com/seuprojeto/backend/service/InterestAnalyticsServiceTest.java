package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.AnalyticsProperties;
import com.seuprojeto.backend.dto.InterestSummaryResponse;
import com.seuprojeto.backend.model.InterestGrouping;
import com.seuprojeto.backend.repository.ChunkRetrievalLogRepository;
import com.seuprojeto.backend.repository.InterestSummaryRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterestAnalyticsServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T18:00:00Z");

    private ChunkRetrievalLogRepository repository;
    private InterestAnalyticsService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChunkRetrievalLogRepository.class);
        service = new InterestAnalyticsService(repository,
                new AnalyticsProperties(Duration.ofHours(24), 50), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void summarise_noWindowGiven_usesTheConfiguredDefaultAndEchoesItBack() {
        InterestSummaryResponse response = service.summarise(null, null, InterestGrouping.TITLE_REF);

        assertThat(response.from()).isEqualTo(NOW.minus(Duration.ofHours(24)));
        assertThat(response.to()).isEqualTo(NOW);
        assertThat(response.groupBy()).isEqualTo("titleRef");
        verify(repository).summariseByTitleRef(NOW.minus(Duration.ofHours(24)), NOW);
    }

    @Test
    void summarise_groupByType_usesTheTypeQuery() {
        service.summarise(null, null, InterestGrouping.TYPE);

        verify(repository).summariseByType(any(), any());
        verify(repository, never()).summariseByTitleRef(any(), any());
    }

    @Test
    void summarise_explicitWindow_isPassedThrough() {
        Instant from = Instant.parse("2026-08-19T00:00:00Z");
        Instant to = Instant.parse("2026-08-19T12:00:00Z");

        service.summarise(from, to, InterestGrouping.TITLE_REF);

        verify(repository).summariseByTitleRef(from, to);
    }

    @Test
    void summarise_mapsRowsAndRoundsTheAverageScore() {
        when(repository.summariseByTitleRef(any(), any())).thenReturn(List.of(
                row("Salim Ismail", 42, 0.7836666, 19)));

        InterestSummaryResponse response = service.summarise(null, null, InterestGrouping.TITLE_REF);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().getFirst().key()).isEqualTo("Salim Ismail");
        assertThat(response.results().getFirst().retrievalCount()).isEqualTo(42);
        assertThat(response.results().getFirst().avgScore()).isEqualTo(0.784);
        assertThat(response.results().getFirst().distinctSessions()).isEqualTo(19);
        assertThat(response.truncated()).isFalse();
    }

    @Test
    void summarise_moreGroupsThanTheCap_isTruncatedAndSaysSo() {
        service = new InterestAnalyticsService(repository,
                new AnalyticsProperties(Duration.ofHours(24), 2), Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.summariseByTitleRef(any(), any())).thenReturn(
                IntStream.range(0, 5).mapToObj(i -> row("item " + i, 5 - i, 0.5, 1)).toList());

        InterestSummaryResponse response = service.summarise(null, null, InterestGrouping.TITLE_REF);

        assertThat(response.results()).hasSize(2);
        assertThat(response.truncated()).isTrue();
    }

    @Test
    void summarise_invertedWindow_throwsRatherThanSilentlySwapping() {
        Instant from = Instant.parse("2026-08-19T12:00:00Z");
        Instant to = Instant.parse("2026-08-19T00:00:00Z");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.summarise(from, to, InterestGrouping.TYPE))
                .withMessageContaining("anterior");
    }

    @Test
    void summarise_emptyWindow_throws() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.summarise(NOW, NOW, InterestGrouping.TYPE));
    }

    @Test
    void fromWire_acceptsBothGroupings_caseInsensitively() {
        assertThat(InterestGrouping.fromWire("type")).isEqualTo(InterestGrouping.TYPE);
        assertThat(InterestGrouping.fromWire("titleref")).isEqualTo(InterestGrouping.TITLE_REF);
        assertThat(InterestGrouping.fromWire(" titleRef ")).isEqualTo(InterestGrouping.TITLE_REF);
    }

    @Test
    void fromWire_unknownGrouping_throwsListingWhatIsAccepted() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> InterestGrouping.fromWire("speaker"))
                .withMessageContaining("type")
                .withMessageContaining("titleRef");
    }

    private static InterestSummaryRow row(String key, long count, double avgScore, long sessions) {
        return new InterestSummaryRow() {
            public String getGroupKey() { return key; }
            public long getRetrievalCount() { return count; }
            public double getAvgScore() { return avgScore; }
            public long getDistinctSessions() { return sessions; }
        };
    }
}

package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.AgendaProperties;
import com.seuprojeto.backend.config.IngestionProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class EventDateConsistencyCheckTest {

    /** The corpus fixture says nothing about data_iso; the real corpus says 2026-08-26. */
    private static final String REAL_CORPUS = "data/evento.json";

    @Test
    void run_propertyAgreesWithTheCorpus_passes() {
        assertThatCode(() -> check(REAL_CORPUS, LocalDate.of(2026, 8, 26)).run(null))
                .doesNotThrowAnyException();
    }

    @Test
    void run_propertyPointsAtAnotherDay_failsTheBootNamingBothValues() {
        // Left alone, the date filter would answer "no programme that day" for the real event day —
        // a confident wrong answer, which is worse than not starting.
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> check(REAL_CORPUS, LocalDate.of(2026, 9, 10)).run(null))
                .withMessageContaining("2026-09-10")
                .withMessageContaining("2026-08-26");
    }

    @Test
    void run_corpusWithoutDataIso_isNotAMismatch() {
        // Nothing to compare is not the same as disagreeing.
        assertThatCode(() -> check("src/test/resources/fixtures/evento-small.json",
                LocalDate.of(2026, 9, 10)).run(null)).doesNotThrowAnyException();
    }

    @Test
    void run_unreadableCorpus_isLeftToStartupIngestionToReport() {
        assertThatCode(() -> check("data/nao-existe.json", LocalDate.of(2026, 8, 26)).run(null))
                .doesNotThrowAnyException();
    }

    @Test
    void run_ingestionDisabledWithoutAPath_doesNothing() {
        assertThatCode(() -> new EventDateConsistencyCheck(new IngestionProperties(false, null),
                agenda(LocalDate.of(2026, 8, 26))).run(null)).doesNotThrowAnyException();
    }

    private static EventDateConsistencyCheck check(String path, LocalDate configured) {
        return new EventDateConsistencyCheck(new IngestionProperties(true, path), agenda(configured));
    }

    private static AgendaProperties agenda(LocalDate eventDate) {
        return new AgendaProperties(15, 0.8, 5, Duration.ofMinutes(45),
                List.of("agenda", "agenda_subsessao"), eventDate);
    }
}

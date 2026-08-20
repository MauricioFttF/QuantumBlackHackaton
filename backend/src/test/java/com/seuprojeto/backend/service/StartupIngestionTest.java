package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.IngestionProperties;
import com.seuprojeto.backend.error.EmbeddingException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StartupIngestionTest {

    private final IngestionService ingestionService = mock(IngestionService.class);

    @Test
    void run_enabled_ingestsTheConfiguredPath() throws IOException {
        when(ingestionService.ingestFromJsonFile("data/evento.json"))
                .thenReturn(new IngestionResult(23, 0, 23));

        runner(true, "data/evento.json").run(null);

        verify(ingestionService).ingestFromJsonFile("data/evento.json");
    }

    @Test
    void run_disabled_ingestsNothing() throws IOException {
        runner(false, "data/evento.json").run(null);

        verify(ingestionService, never()).ingestFromJsonFile(anyString());
    }

    @Test
    void run_ingestionFails_doesNotPreventStartup() throws IOException {
        // Registration, login and GET /api/chunks do not need the corpus, and a chat without
        // chunks already answers that it does not know. A dead deployment would be worse.
        when(ingestionService.ingestFromJsonFile(anyString()))
                .thenThrow(new EmbeddingException("Gemini is down"));

        assertThatCode(() -> runner(true, "data/evento.json").run(null)).doesNotThrowAnyException();
    }

    @Test
    void run_missingFile_doesNotPreventStartup() throws IOException {
        when(ingestionService.ingestFromJsonFile(anyString()))
                .thenThrow(new IOException("no such file"));

        assertThatCode(() -> runner(true, "data/nope.json").run(null)).doesNotThrowAnyException();
    }

    @Test
    void constructor_enabledWithoutAPath_isRejectedAtStartup() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new IngestionProperties(true, "  "))
                .withMessageContaining("app.ingestion.path");
    }

    private StartupIngestion runner(boolean onStartup, String path) {
        return new StartupIngestion(ingestionService, new IngestionProperties(onStartup, path));
    }
}

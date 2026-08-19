package com.seuprojeto.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seuprojeto.backend.config.GeminiProperties;
import com.seuprojeto.backend.error.EmbeddingException;
import com.seuprojeto.backend.model.ChunkDraft;
import com.seuprojeto.backend.model.KnowledgeChunk;
import com.seuprojeto.backend.repository.KnowledgeChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the ingestion pipeline. Gemini and the database are both stubbed — no
 * network, no Spring context, no API key needed.
 */
class IngestionServiceTest {

    private static final String SMALL_FIXTURE = fixture("fixtures/evento-small.json");
    private static final String DUPLICATES_FIXTURE = fixture("fixtures/evento-duplicates.json");

    private KnowledgeChunkRepository repository;
    private EmbeddingService embeddingService;
    private IngestionService service;

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeChunkRepository.class);
        embeddingService = mock(EmbeddingService.class);
        when(repository.findAllContentHashes()).thenReturn(List.of());
        when(embeddingService.embed(anyString())).thenReturn(deterministicVector());
        service = new IngestionService(repository, embeddingService, properties(768));
    }

    @Test
    void ingestFromJsonFile_emptyDatabase_embedsAndSavesEveryChunk() throws IOException {
        IngestionResult result = service.ingestFromJsonFile(SMALL_FIXTURE);

        assertThat(result).isEqualTo(new IngestionResult(4, 0, 4));
        verify(embeddingService, times(4)).embed(anyString());

        List<KnowledgeChunk> saved = captureSaved();
        assertThat(saved).hasSize(4);
        assertThat(saved).allSatisfy(chunk -> {
            assertThat(chunk.getEmbedding()).hasSize(KnowledgeChunk.EMBEDDING_DIMENSIONS);
            assertThat(chunk.getContentHash()).hasSize(64);
            assertThat(chunk.getContent()).isNotBlank();
        });
        assertThat(saved).extracting(KnowledgeChunk::getType)
                .containsExactly("evento", "agenda", "agenda", "palestrante");
        assertThat(saved).extracting(KnowledgeChunk::getContentHash).doesNotHaveDuplicates();
    }

    @Test
    void ingestFromJsonFile_allChunksAlreadyStored_skipsEverythingWithoutEmbedding() throws IOException {
        when(repository.findAllContentHashes()).thenReturn(hashesOf(SMALL_FIXTURE));

        IngestionResult result = service.ingestFromJsonFile(SMALL_FIXTURE);

        assertThat(result).isEqualTo(new IngestionResult(0, 4, 4));
        // The whole point of hashing before embedding: a re-ingest spends no API quota.
        verify(embeddingService, never()).embed(anyString());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void ingestFromJsonFile_someChunksAlreadyStored_embedsOnlyTheNewOnes() throws IOException {
        when(repository.findAllContentHashes()).thenReturn(hashesOf(SMALL_FIXTURE).subList(0, 2));

        IngestionResult result = service.ingestFromJsonFile(SMALL_FIXTURE);

        assertThat(result).isEqualTo(new IngestionResult(2, 2, 4));
        verify(embeddingService, times(2)).embed(anyString());
        assertThat(captureSaved()).extracting(KnowledgeChunk::getType)
                .containsExactly("agenda", "palestrante");
    }

    @Test
    void ingestFromJsonFile_duplicateChunksInSameFile_savesOnlyOne() throws IOException {
        // Would otherwise violate the unique constraint on content_hash.
        IngestionResult result = service.ingestFromJsonFile(DUPLICATES_FIXTURE);

        assertThat(result).isEqualTo(new IngestionResult(1, 1, 2));
        assertThat(captureSaved()).hasSize(1);
    }

    @Test
    void ingestFromJsonFile_embeddingFails_propagatesAndSavesNothing() {
        when(embeddingService.embed(anyString())).thenThrow(new EmbeddingException("Gemini is down"));

        assertThatExceptionOfType(EmbeddingException.class)
                .isThrownBy(() -> service.ingestFromJsonFile(SMALL_FIXTURE))
                .withMessageContaining("Gemini is down");

        // A partially embedded corpus is worse than none.
        verify(repository, never()).saveAll(any());
    }

    @Test
    void ingestFromJsonFile_missingFile_throwsIOException() {
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> service.ingestFromJsonFile("does/not/exist.json"));

        verify(embeddingService, never()).embed(anyString());
    }

    @Test
    void constructor_configuredDimensionDiffersFromColumn_failsFast() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new IngestionService(repository, embeddingService, properties(1536)))
                .withMessageContaining("gemini.embedding-dimensions is 1536")
                .withMessageContaining("vector(768)");
    }

    private List<KnowledgeChunk> captureSaved() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        return captor.getValue();
    }

    /** Hashes the fixture through the same pure stage the service uses. */
    private static List<String> hashesOf(String path) throws IOException {
        var data = new ObjectMapper().readValue(
                Paths.get(path).toFile(), com.seuprojeto.backend.dto.EventDataDTO.class);
        return IngestionService.toDrafts(data).stream().map(ChunkDraft::contentHash).toList();
    }

    private static float[] deterministicVector() {
        float[] vector = new float[KnowledgeChunk.EMBEDDING_DIMENSIONS];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = i / 1000f;
        }
        return vector;
    }

    private static GeminiProperties properties(int dimensions) {
        return new GeminiProperties("fake-key", "https://example.invalid/v1beta",
                "gemini-embedding-001", dimensions, "gemini-3.7-flash", 0.2, 1024,
                Duration.ofSeconds(5), Duration.ofSeconds(20), Duration.ofSeconds(60),
                1, Duration.ZERO, Duration.ofSeconds(30));
    }

    private static String fixture(String name) {
        try {
            return Paths.get(IngestionServiceTest.class.getClassLoader().getResource(name).toURI()).toString();
        } catch (Exception e) {
            throw new IllegalStateException("Missing test fixture: " + name, e);
        }
    }
}

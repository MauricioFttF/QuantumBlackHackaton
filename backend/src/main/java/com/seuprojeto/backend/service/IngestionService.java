package com.seuprojeto.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seuprojeto.backend.config.GeminiProperties;
import com.seuprojeto.backend.dto.EventDataDTO;
import com.seuprojeto.backend.model.ChunkDraft;
import com.seuprojeto.backend.model.KnowledgeChunk;
import com.seuprojeto.backend.repository.KnowledgeChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ingestion pipeline: JSON source -> text chunks -> embeddings -> database.
 *
 * <p>Stages are deliberately separate. {@link #toDrafts} is pure and deterministic; only the
 * embedding stage touches the network, and only the final {@code saveAll} touches the database.
 *
 * <p>Idempotent: a chunk whose content hash is already stored is skipped <em>before</em> it is
 * embedded, so re-running an ingest costs no API quota. If any embedding fails the exception
 * propagates and nothing is written — a partially embedded corpus is worse than none.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final KnowledgeChunkRepository repository;
    private final EmbeddingService embeddingService;
    // Constructed directly rather than injected: Boot 4 auto-configures a Jackson 3
    // (tools.jackson) ObjectMapper, while this project parses with Jackson 2.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IngestionService(KnowledgeChunkRepository repository,
                            EmbeddingService embeddingService,
                            GeminiProperties geminiProperties) {
        // The column width is a compile-time constant; the API dimension is configuration.
        // Catch a mismatch at startup instead of on the first INSERT.
        if (geminiProperties.embeddingDimensions() != KnowledgeChunk.EMBEDDING_DIMENSIONS) {
            throw new IllegalStateException(
                    "gemini.embedding-dimensions is %d but the knowledge_chunk.embedding column is vector(%d). "
                            .formatted(geminiProperties.embeddingDimensions(), KnowledgeChunk.EMBEDDING_DIMENSIONS)
                            + "Change KnowledgeChunk.EMBEDDING_DIMENSIONS and migrate the column to match.");
        }
        this.repository = repository;
        this.embeddingService = embeddingService;
    }

    public IngestionResult ingestFromJsonFile(String path) throws IOException {
        EventDataDTO data = objectMapper.readValue(new File(path), EventDataDTO.class);
        List<ChunkDraft> drafts = toDrafts(data);

        Set<String> knownHashes = new HashSet<>(repository.findAllContentHashes());
        List<KnowledgeChunk> toSave = new ArrayList<>();
        int skipped = 0;

        for (ChunkDraft draft : drafts) {
            String hash = draft.contentHash();
            // knownHashes grows as we go, so duplicates inside a single file are also collapsed
            // rather than blowing up on the unique constraint.
            if (!knownHashes.add(hash)) {
                skipped++;
                continue;
            }
            float[] embedding = embeddingService.embed(draft.content());
            toSave.add(new KnowledgeChunk(draft.type(), draft.titleRef(), draft.content(), embedding, hash));
        }

        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
        }

        log.info("Ingested {}: {} created, {} skipped, {} total", path, toSave.size(), skipped, drafts.size());
        return new IngestionResult(toSave.size(), skipped, drafts.size());
    }

    /**
     * Flattens the source document into chunks. Pure: no I/O, no randomness, no clock.
     *
     * <p>Every chunk's text begins with its type in words ("Artigo: ...", "Palestrante: ..."),
     * because the {@code type} column is invisible to the embedding model. Without it a question
     * about "artigos" has nothing to match and articles rank below unrelated agenda items.
     * Changing this text changes every content hash, so it requires a full re-ingestion.
     */
    static List<ChunkDraft> toDrafts(EventDataDTO data) {
        List<ChunkDraft> drafts = new ArrayList<>();

        // Evento (dados gerais)
        if (data.getEvento() != null) {
            String content = "Evento — informações gerais. Tema: " + data.getEvento().getTema_geral()
                    + " | Data: " + data.getEvento().getData()
                    + " | Local: " + data.getEvento().getLocal();
            drafts.add(new ChunkDraft("evento", "Informações Gerais", content));
        }

        // Agenda
        if (data.getAgenda() != null) {
            for (var item : data.getAgenda()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Agenda do evento — Horário: ").append(item.getHorario())
                        .append(" — ").append(item.getTema_da_palestra());
                if (item.getPalestrante_relacionado() != null) {
                    sb.append(" (Palestrante: ").append(item.getPalestrante_relacionado()).append(")");
                }
                drafts.add(new ChunkDraft("agenda", item.getHorario(), sb.toString()));
            }
        }

        // Palestrantes
        if (data.getPalestrantes() != null) {
            for (var p : data.getPalestrantes()) {
                String content = "Palestrante: " + p.getNome() + " — " + p.getCargo() + ". " + p.getBiografia();
                drafts.add(new ChunkDraft("palestrante", p.getNome(), content));
            }
        }

        // Artigos
        if (data.getArtigos() != null) {
            for (var a : data.getArtigos()) {
                String content = "Artigo: " + a.getTitulo_traduzido()
                        + " (" + a.getTitulo_original() + "). " + a.getResumo();
                drafts.add(new ChunkDraft("artigo", a.getTitulo_traduzido(), content));
            }
        }

        // Matérias
        if (data.getMaterias() != null) {
            for (var m : data.getMaterias()) {
                String content = "Matéria / notícia: " + m.getTitulo() + " (" + m.getData() + "). Participantes: "
                        + m.getParticipantes_mencionados() + ". " + m.getResumo();
                drafts.add(new ChunkDraft("materia", m.getTitulo(), content));
            }
        }

        return drafts;
    }
}

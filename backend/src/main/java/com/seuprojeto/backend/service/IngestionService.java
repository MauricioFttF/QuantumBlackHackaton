package com.seuprojeto.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seuprojeto.backend.config.GeminiProperties;
import com.seuprojeto.backend.dto.EventDataDTO;
import com.seuprojeto.backend.model.ChunkDraft;
import com.seuprojeto.backend.model.KnowledgeChunk;
import com.seuprojeto.backend.error.ConcurrentIngestionException;
import com.seuprojeto.backend.repository.KnowledgeChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
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
        EventDataDTO data = objectMapper.readValue(resolveWithinWorkingDirectory(path).toFile(),
                EventDataDTO.class);
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
            try {
                repository.saveAll(toSave);
            } catch (DataIntegrityViolationException e) {
                // findAllContentHashes is a read, so two concurrent ingests of the same source
                // both pass the check and race here. The unique constraint keeps the data
                // correct; this turns the resulting crash into an explainable 409.
                throw new ConcurrentIngestionException(
                        "Outra ingestão gravou estes chunks ao mesmo tempo. Nada foi duplicado; "
                                + "rode novamente para confirmar o estado.", e);
            }
        }

        log.info("Ingested {}: {} created, {} skipped, {} total", path, toSave.size(), skipped, drafts.size());
        return new IngestionResult(toSave.size(), skipped, drafts.size());
    }

    /**
     * Resolves a client-supplied path against the working directory and refuses anything that
     * escapes it. Without this, {@code ?path=../../etc/passwd} or an absolute path would be
     * handed straight to the file reader.
     *
     * <p>Two checks, because one is not enough. The lexical check rejects the obvious traversal
     * before touching the filesystem; {@link Path#toRealPath} then resolves symbolic links, which
     * normalization cannot see — a link <em>inside</em> the working directory pointing outside it
     * passes the first check and fails the second. Resolving the real path also means a
     * non-existent file fails here as an {@link java.nio.file.NoSuchFileException} rather than
     * later in the reader; both are {@code IOException} and map to the same 400.
     */
    static Path resolveWithinWorkingDirectory(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("O parâmetro 'path' não pode ser vazio");
        }
        Path base = Path.of("").toAbsolutePath().normalize();
        Path resolved = base.resolve(path).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException(
                    "O parâmetro 'path' deve apontar para um arquivo dentro do diretório da aplicação");
        }
        if (!resolved.toRealPath().startsWith(base.toRealPath())) {
            throw new IllegalArgumentException(
                    "O parâmetro 'path' deve apontar para um arquivo dentro do diretório da aplicação");
        }
        return resolved;
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
            var e = data.getEvento();
            StringBuilder sb = new StringBuilder("Evento — informações gerais. ");
            sb.append(e.getNome() != null ? e.getNome() + ". " : "");
            sb.append(e.getTema_geral() != null ? "Tema: " + e.getTema_geral() + " " : "");
            sb.append(e.getDescricao_longa() != null ? e.getDescricao_longa() + " " : "");
            if (e.getData_extenso() != null) {
                sb.append("Data: ").append(e.getData_extenso());
                if (e.getHorario_inicio() != null) {
                    sb.append(", das ").append(e.getHorario_inicio()).append(" às ").append(e.getHorario_fim());
                }
                sb.append(". ");
            }
            if (e.getLocal() != null) {
                sb.append("Local: ").append(e.getLocal().getNome())
                        .append(", ").append(e.getLocal().getEndereco())
                        .append(", ").append(e.getLocal().getCidade()).append("/").append(e.getLocal().getEstado());
            }
            drafts.add(new ChunkDraft("evento", "Informações Gerais", sb.toString()));
        }

        // Agenda
        if (data.getAgenda() != null) {
            for (var item : data.getAgenda()) {
                StringBuilder sb = new StringBuilder("Agenda do evento — Horário: ")
                        .append(item.getHorario_inicio());
                if (item.getHorario_fim() != null) {
                    sb.append(" às ").append(item.getHorario_fim());
                }
                sb.append(" — ").append(item.getTitulo());
                if (item.getDescricao() != null) {
                    sb.append(". ").append(item.getDescricao());
                }
                drafts.add(new ChunkDraft("agenda", item.getTitulo(), sb.toString()));

                if (item.getSubsessoes() != null) {
                    for (var sub : item.getSubsessoes()) {
                        String subContent = "Sessão temática (dentro de '" + item.getTitulo() + "', "
                                + item.getHorario_inicio() + "): " + sub.getTitulo()
                                + (sub.getDescricao() != null ? ". " + sub.getDescricao() : "");
                        drafts.add(new ChunkDraft("agenda_subsessao", sub.getTitulo(), subContent));
                    }
                }
            }
        }

        // Palestrantes
        if (data.getPalestrantes() != null) {
            for (var p : data.getPalestrantes()) {
                String content = "Palestrante: " + p.getNome() + " — " + p.getCargo()
                        + (p.getEmpresa() != null ? " (" + p.getEmpresa() + ")" : "")
                        + ". " + p.getBiografia();
                drafts.add(new ChunkDraft("palestrante", p.getNome(), content));
            }
        }

        // Artigos: introdução + cada ponto-chave/pilar/estatística/acelerador vira um chunk próprio
        if (data.getArtigos() != null) {
            for (var a : data.getArtigos()) {
                String tituloRef = a.getTitulo_traduzido();

                String introContent = "Artigo: " + a.getTitulo_traduzido()
                        + " (" + a.getTitulo_original() + "). " + a.getIntroducao();
                drafts.add(new ChunkDraft("artigo", tituloRef, introContent));

                if (a.getPontos_chave() != null) {
                    for (var pc : a.getPontos_chave()) {
                        String content = "Artigo '" + tituloRef + "' — Ponto " + pc.getNumero()
                                + ": " + pc.getTitulo() + ". " + pc.getConteudo();
                        drafts.add(new ChunkDraft("artigo_ponto_chave", tituloRef + " #" + pc.getNumero(), content));
                    }
                }

                if (a.getPilares_estrategicos() != null) {
                    for (var pil : a.getPilares_estrategicos()) {
                        StringBuilder sb = new StringBuilder("Artigo '" + tituloRef + "' — Pilar "
                                + pil.getNumero() + ": " + pil.getTitulo() + ". " + pil.getDescricao());
                        if (pil.getSetores() != null) {
                            for (var setor : pil.getSetores()) {
                                sb.append(" | Setor - ").append(setor.getNome()).append(": ").append(setor.getDescricao());
                            }
                        }
                        drafts.add(new ChunkDraft("artigo_pilar", tituloRef + " #" + pil.getNumero(), sb.toString()));
                    }
                }

                if (a.getEstatisticas_chave() != null) {
                    for (var est : a.getEstatisticas_chave()) {
                        String content = "Artigo '" + tituloRef + "' — Estatística: " + est.getTitulo()
                                + ". " + est.getDado()
                                + (est.getFonte_citada() != null ? " (Fonte: " + est.getFonte_citada() + ")" : "");
                        drafts.add(new ChunkDraft("artigo_estatistica", tituloRef + " - " + est.getTitulo(), content));
                    }
                }

                if (a.getAceleradores_de_crescimento() != null) {
                    for (var ac : a.getAceleradores_de_crescimento()) {
                        String content = "Artigo '" + tituloRef + "' — Acelerador: " + ac.getTitulo()
                                + ". " + ac.getDescricao();
                        drafts.add(new ChunkDraft("artigo_acelerador", tituloRef + " - " + ac.getTitulo(), content));
                    }
                }
            }
        }

        // Matérias de imprensa
        if (data.getMaterias_imprensa() != null) {
            for (var m : data.getMaterias_imprensa()) {
                StringBuilder participantesStr = new StringBuilder();
                if (m.getParticipantes() != null) {
                    for (var part : m.getParticipantes()) {
                        if (participantesStr.length() > 0) participantesStr.append(", ");
                        participantesStr.append(part.getNome()).append(" (").append(part.getCargo()).append(")");
                    }
                }
                String content = "Matéria / notícia: " + m.getTitulo()
                        + (m.getData() != null ? " (" + m.getData() + ")" : "")
                        + ". Participantes: " + participantesStr + ". " + m.getResumo();
                drafts.add(new ChunkDraft("materia", m.getTitulo(), content));
            }
        }

        // FAQ sugerido: pares pergunta/resposta viram chunks diretos (excelente para recall no RAG)
        if (data.getFaq_sugerido() != null) {
            for (var faq : data.getFaq_sugerido()) {
                String content = "Pergunta frequente: " + faq.getPergunta() + " Resposta: " + faq.getResposta();
                drafts.add(new ChunkDraft("faq", faq.getPergunta(), content));
            }
        }

        return drafts;
    }
}

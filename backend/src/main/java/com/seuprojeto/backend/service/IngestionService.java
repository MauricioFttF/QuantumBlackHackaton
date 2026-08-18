package com.seuprojeto.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seuprojeto.backend.dto.EventDataDTO;
import com.seuprojeto.backend.model.KnowledgeChunk;
import com.seuprojeto.backend.repository.KnowledgeChunkRepository;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class IngestionService {

    private final KnowledgeChunkRepository repository;

    public IngestionService(KnowledgeChunkRepository repository) {
        this.repository = repository;
    }

    public int ingestFromJsonFile(String path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        EventDataDTO data = mapper.readValue(new File(path), EventDataDTO.class);

        List<KnowledgeChunk> chunks = new ArrayList<>();

        // Evento (dados gerais)
        if (data.getEvento() != null) {
            String content = "Evento: " + data.getEvento().getTema_geral()
                    + " | Data: " + data.getEvento().getData()
                    + " | Local: " + data.getEvento().getLocal();
            chunks.add(new KnowledgeChunk("evento", "Informações Gerais", content));
        }

        // Agenda
        if (data.getAgenda() != null) {
            for (var item : data.getAgenda()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Horário: ").append(item.getHorario())
                        .append(" — ").append(item.getTema_da_palestra());
                if (item.getPalestrante_relacionado() != null) {
                    sb.append(" (Palestrante: ").append(item.getPalestrante_relacionado()).append(")");
                }
                chunks.add(new KnowledgeChunk("agenda", item.getHorario(), sb.toString()));
            }
        }

        // Palestrantes
        if (data.getPalestrantes() != null) {
            for (var p : data.getPalestrantes()) {
                String content = p.getNome() + " — " + p.getCargo() + ". " + p.getBiografia();
                chunks.add(new KnowledgeChunk("palestrante", p.getNome(), content));
            }
        }

        // Artigos
        if (data.getArtigos() != null) {
            for (var a : data.getArtigos()) {
                String content = a.getTitulo_traduzido() + " (" + a.getTitulo_original() + "). " + a.getResumo();
                chunks.add(new KnowledgeChunk("artigo", a.getTitulo_traduzido(), content));
            }
        }

        // Matérias
        if (data.getMaterias() != null) {
            for (var m : data.getMaterias()) {
                String content = m.getTitulo() + " (" + m.getData() + "). Participantes: "
                        + m.getParticipantes_mencionados() + ". " + m.getResumo();
                chunks.add(new KnowledgeChunk("materia", m.getTitulo(), content));
            }
        }

        repository.saveAll(chunks);
        return chunks.size();
    }
}
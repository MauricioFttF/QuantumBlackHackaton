package com.seuprojeto.backend.controller;

import com.seuprojeto.backend.model.KnowledgeChunk;
import com.seuprojeto.backend.repository.KnowledgeChunkRepository;
import com.seuprojeto.backend.service.IngestionService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class KnowledgeChunkController {

    private final KnowledgeChunkRepository repository;
    private final IngestionService ingestionService;

    public KnowledgeChunkController(KnowledgeChunkRepository repository, IngestionService ingestionService) {
        this.repository = repository;
        this.ingestionService = ingestionService;
    }

    @GetMapping("/chunks")
    public List<KnowledgeChunk> listAll() {
        return repository.findAll();
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestParam String path) throws IOException {
        int count = ingestionService.ingestFromJsonFile(path);
        return Map.of("status", "ok", "chunksCreated", count);
    }
}
package com.seuprojeto.backend.controller;

import com.seuprojeto.backend.model.KnowledgeChunk;
import com.seuprojeto.backend.repository.KnowledgeChunkRepository;
import com.seuprojeto.backend.service.IngestionResult;
import com.seuprojeto.backend.service.IngestionService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

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
    public IngestionResult ingest(@RequestParam String path) throws IOException {
        return ingestionService.ingestFromJsonFile(path);
    }
}
package com.seuprojeto.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Startup ingestion.
 *
 * <p>Exists because {@code POST /api/ingest} needs an account, and requiring an evaluator to
 * register before the corpus loads would make a clean {@code docker compose up} answer "não
 * encontrei essa informação" to every question — a working system that looks broken.
 *
 * @param onStartup whether to load {@code path} when the application boots. Idempotent: chunks
 *                  already stored are skipped before they are embedded, so a restart costs no
 *                  API quota
 * @param path      the corpus, relative to the working directory
 */
@ConfigurationProperties(prefix = "app.ingestion")
public record IngestionProperties(boolean onStartup, String path) {

    public IngestionProperties {
        if (onStartup && (path == null || path.isBlank())) {
            throw new IllegalArgumentException(
                    "app.ingestion.path must be set when app.ingestion.on-startup is true");
        }
    }
}

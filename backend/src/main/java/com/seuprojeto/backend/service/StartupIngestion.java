package com.seuprojeto.backend.service;

import com.seuprojeto.backend.config.IngestionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Loads the corpus once, at boot, so a fresh {@code docker compose up} is immediately usable.
 *
 * <p>Safe to run every start: {@link IngestionService} hashes each chunk before embedding it, so a
 * second boot writes nothing and spends no quota.
 *
 * <p><b>A failure here logs and lets the application start.</b> That is a deliberate exception,
 * narrower than it looks: the endpoints that do not need the corpus — registration, login,
 * {@code GET /api/chunks} — must still work, and a chat with no chunks already answers honestly
 * that it does not know rather than inventing anything. Failing the boot instead would turn a
 * transient provider outage into a dead deployment. The error is logged at ERROR level with the
 * cause, and {@code GET /api/chunks} shows an empty corpus, so the state is visible rather than
 * disguised.
 */
@Component
public class StartupIngestion implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupIngestion.class);

    private final IngestionService ingestionService;
    private final IngestionProperties properties;

    public StartupIngestion(IngestionService ingestionService, IngestionProperties properties) {
        this.ingestionService = ingestionService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.onStartup()) {
            log.info("Startup ingestion disabled; POST /api/ingest still works");
            return;
        }

        try {
            IngestionResult result = ingestionService.ingestFromJsonFile(properties.path());
            if (result.created() == 0) {
                log.info("Corpus already loaded: {} chunk(s) present, nothing embedded", result.skipped());
            } else {
                log.info("Loaded the corpus from {}: {} chunk(s) created, {} already present",
                        properties.path(), result.created(), result.skipped());
            }
        } catch (Exception e) {
            log.error("Startup ingestion of {} failed. The application is up, but the corpus may be "
                    + "empty or partial — check GET /api/chunks and re-run POST /api/ingest once the "
                    + "cause is fixed.", properties.path(), e);
        }
    }
}

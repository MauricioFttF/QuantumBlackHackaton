package com.seuprojeto.backend.service;

/**
 * Outcome of one ingestion run.
 *
 * @param created chunks embedded and written on this run
 * @param skipped chunks already present, identified by content hash — never re-embedded
 * @param total   chunks found in the source
 */
public record IngestionResult(int created, int skipped, int total) {
}

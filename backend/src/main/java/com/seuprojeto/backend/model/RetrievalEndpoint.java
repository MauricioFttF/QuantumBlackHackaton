package com.seuprojeto.backend.model;

/**
 * Which endpoint retrieved a chunk. Persisted by these exact lowercase names and pinned by a CHECK
 * constraint in {@code V5__chunk_retrieval_log.sql}, so renaming one is a migration.
 */
public enum RetrievalEndpoint {

    CHAT("chat"),
    AGENDA_RECOMMEND("agenda_recommend");

    private final String storedName;

    RetrievalEndpoint(String storedName) {
        this.storedName = storedName;
    }

    /** The value written to {@code chunk_retrieval_log.endpoint}. */
    public String storedName() {
        return storedName;
    }
}

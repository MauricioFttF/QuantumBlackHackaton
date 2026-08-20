-- Aggregate interest analytics: one row per chunk that was actually sent to the model as context.
--
-- Named V5 rather than the V2 the spec suggested because V2, V3 and V4 already exist
-- (conversation memory, accounts, sessions).
--
-- Deliberately NOT a transcript store: there is no column for the question text, and session_ref
-- is a per-request opaque id, so nothing here reconstructs what anyone asked or ties rows to a
-- person. Per-user analytics would be a separate table with its own retention decision, not a
-- side effect of this one.

CREATE TABLE chunk_retrieval_log (
    id          BIGSERIAL PRIMARY KEY,
    chunk_id    BIGINT      NOT NULL REFERENCES knowledge_chunk (id),
    endpoint    VARCHAR(50) NOT NULL,
    score       DOUBLE PRECISION NOT NULL,
    -- Opaque per-request id. Lets "42 retrievals across 19 requests" be counted without knowing
    -- who made them.
    session_ref VARCHAR(100),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    -- Not in the spec's DDL, added for the same reason V2 constrains role: the vocabulary is
    -- closed and a typo in application code should fail here rather than quietly create a third
    -- endpoint name that no dashboard query knows about.
    CONSTRAINT ck_chunk_retrieval_log_endpoint CHECK (endpoint IN ('chat', 'agenda_recommend'))
);

CREATE INDEX idx_chunk_retrieval_log_chunk ON chunk_retrieval_log (chunk_id);
CREATE INDEX idx_chunk_retrieval_log_created_at ON chunk_retrieval_log (created_at);

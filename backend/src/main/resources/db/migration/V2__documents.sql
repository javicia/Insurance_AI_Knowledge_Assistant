-- Document Management bounded context schema (FASE 4). Mirrors the Document aggregate
-- (documents + document_versions, loaded/saved together) and the independent DocumentChunk
-- aggregate (document_chunks), see docs/adr/ADR-003-DOCUMENT-AGGREGATE-BOUNDARIES.md.
-- No embedding/vector columns here: those belong to V3__vector_store.sql (FASE 5).

CREATE TABLE documents (
    id              UUID PRIMARY KEY,
    name            TEXT NOT NULL,
    type            VARCHAR(32) NOT NULL,
    product         TEXT,
    country         TEXT,
    language        TEXT,
    classification  VARCHAR(32) NOT NULL,
    source          TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document_versions (
    id                  UUID PRIMARY KEY,
    document_id         UUID NOT NULL REFERENCES documents (id),
    version_major       INTEGER NOT NULL,
    version_minor       INTEGER NOT NULL,
    content_hash        CHAR(64) NOT NULL,
    effective_from      TIMESTAMPTZ NOT NULL,
    effective_to        TIMESTAMPTZ,
    status              VARCHAR(16) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Idempotency backstop (ADR-004): identical content can never be stored as two versions,
    -- even under concurrent uploads racing past an application-level check.
    CONSTRAINT uq_document_versions_content_hash UNIQUE (content_hash),
    -- Mirrors the Document aggregate's own duplicate-version-number invariant at the storage
    -- layer; overlapping effective_period is enforced by the aggregate only (see ADR-003),
    -- not expressible as a simple SQL constraint without range types.
    CONSTRAINT uq_document_versions_number UNIQUE (document_id, version_major, version_minor)
);

CREATE INDEX ix_document_versions_document_id ON document_versions (document_id);

-- Raw uploaded bytes, kept separate from document_versions (see DocumentContentStore port):
-- the Document/DocumentVersion domain objects never carry raw content as a field - this is a
-- storage-only concern read by the Kafka consumer that performs extraction.
CREATE TABLE document_version_contents (
    document_version_id    UUID PRIMARY KEY REFERENCES document_versions (id) ON DELETE CASCADE,
    content                 BYTEA NOT NULL
);

CREATE TABLE document_chunks (
    id                      UUID PRIMARY KEY,
    document_id             UUID NOT NULL REFERENCES documents (id),
    document_version_id     UUID NOT NULL REFERENCES document_versions (id) ON DELETE CASCADE,
    chunk_index             INTEGER NOT NULL,
    content                 TEXT NOT NULL,
    page                    INTEGER,
    chapter                 TEXT,
    section                 TEXT,
    paragraph               INTEGER,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Backstop for the replace-semantics idempotent persistence strategy (ADR-004): a version
    -- can never end up with two chunks at the same index even if a processing step somehow
    -- ran concurrently with itself.
    CONSTRAINT uq_document_chunks_version_index UNIQUE (document_version_id, chunk_index)
);

CREATE INDEX ix_document_chunks_version_id ON document_chunks (document_version_id);

# ADR-005: Embedding as a Derived Projection, Not Part of DocumentChunk

## Status

Accepted — FASE 5.

## Context

FASE 3/4 established `DocumentChunk` as its own aggregate (see
`docs/adr/ADR-003-DOCUMENT-AGGREGATE-BOUNDARIES.md`), persisted in `document_chunks`
(PostgreSQL, `V2__documents.sql`) as the authoritative record of a chunk's text and structural
metadata. FASE 5 introduces embeddings and pgvector-backed similarity search. The question:
does the embedding vector belong on `document_chunks` itself, or somewhere else?

## Decision

**The embedding is a derived, denormalized projection of `DocumentChunk`, stored separately
from it**, in the `vector_store` table Spring AI's `PgVectorStore` reads and writes
(`V3__vector_store.sql`) - not as a `vector` column added to `document_chunks`.

### Why not add a vector column to `document_chunks`

- **Bounded context mismatch.** `document_chunks` belongs to Document Management (brief
  section 5); it exists to answer "what text, from what page/section, belongs to this
  version". The embedding exists to answer "what is retrievable by semantic similarity", a RAG
  concern. Coupling them in one table means every embedding-model change (a RAG-context
  decision) forces a migration of a Document-Management table, and vice versa.
- **Model churn.** An embedding is only meaningful together with the exact model that produced
  it (dimension, provider, version - see "Embedding versioning" below). Storing it as a plain
  column would either force `document_chunks` to grow new columns every time the embedding
  model changes, or force destructive in-place updates that lose the ability to run two models
  side by side during a migration.
- **Retrieval access pattern is fundamentally different.** Similarity search never starts from
  "a chunk of a known document" - it starts from "a question", ranks across potentially
  thousands of chunks from many documents, and returns the top-K. That is an index/search
  concern (pgvector + HNSW), not a per-row property of the Document Management schema. This is
  the same reasoning ADR-003 already used to keep `DocumentChunk` out of the `Document`
  aggregate, applied one level deeper.
- **Spring AI's `PgVectorStore` owns its own table shape** (`id`, `content`, `metadata json`,
  `embedding vector(n)` - see `V3__vector_store.sql`) and is not designed to be pointed at an
  arbitrary existing table with extra business columns. Fighting that convention would mean
  reimplementing similarity search by hand instead of using the abstraction brief section 3
  explicitly asks for.

### What actually happens

`vector_store` is a **derived index**, fully rebuildable from `document_chunks` at any time:

- `vector_store.id` = the `DocumentChunk`'s own id (as a UUID string) - not a new identity.
- `vector_store.content` = the chunk's text, duplicated for the sole reason that Spring AI's
  embedding/search machinery requires it structurally (the vector was computed from this exact
  text, and similarity search needs it back to build LLM context and citations without an
  extra join per result).
- `vector_store.metadata` (json) = chunk-level facts only: `documentId`, `documentVersionId`,
  `chunkIndex`, `page`, `chapter`, `section`, `paragraph`, `embeddingModel`,
  `embeddingModelVersion`, `embeddingDimension` (see "Embedding versioning" below). Deliberately
  **not** `documentName`/`versionNumber` - those live on the `Document` aggregate and are
  fetched fresh via `DocumentRepository` when building citations, so a document rename or
  metadata correction is never silently stale in search results (brief section 15: avoid
  duplicating what does not need duplicating).
- `document_chunks` remains the single source of truth for chunk existence and content.
  `vector_store` can be dropped and rebuilt from it entirely by re-running the embedding step -
  this is precisely the reindexing mechanism described below.

This is the standard "read-optimized secondary index" pattern (comparable to a search engine
indexing rows from a system-of-record database) - not an ad hoc choice, and it directly
implements the architectural preference stated for this phase.

## Embedding versioning and reindexing strategy

Every `vector_store` row's `metadata` records `embeddingModel`, `embeddingModelVersion` and
`embeddingDimension` (see `docs/rag/EMBEDDINGS.md`), sourced from
`EmbeddingModelPort#descriptor()` at the moment the chunk was embedded. This is what lets the
system answer "is this vector stale relative to the currently configured model" without
guessing: compare a row's recorded descriptor against the live `EmbeddingModelPort.descriptor()`.

**Reindexing process (documented now, not yet automated - no scheduled job exists in FASE 5):**
when the configured embedding model changes, re-run `EmbedDocumentVersionUseCase` for every
`DocumentVersion` currently `EMBEDDED`, reading content from `document_chunks` (never from the
old `vector_store` rows) and re-adding to `vector_store`. Because `PgVectorStore.add` upserts on
`id` (`ON CONFLICT (id) DO UPDATE`, see `PgVectorStore` source), re-embedding a chunk with the
same `DocumentChunkId` naturally replaces its old vector - no separate delete step is required
in the common case. A full-model-swap operation (delete all rows whose metadata records the old
`embeddingModel`/`embeddingModelVersion`, then reindex) is the safe path when dimensions change,
since a table can only hold one `vector(n)` column width at a time (see
`docs/rag/EMBEDDINGS.md` for the concrete SQL). Automating this as a proper migration
tool/use case is out of scope for FASE 5.

## Consequences

- Positive: `document_chunks` never needs to change shape because of an embedding-model
  decision; `vector_store` never needs to change shape because of a Document Management
  decision.
- Positive: reindexing (change of embedding model) is a well-defined, documented operation
  that reads from one clear source of truth.
- Cost: chunk text is duplicated between `document_chunks` and `vector_store`. Accepted
  explicitly (see above) as the standard, minimal duplication a vector search index requires -
  not a design shortcut.
- Cost: building citations requires a `DocumentRepository` lookup per unique `documentId` in a
  result set (not per chunk) to resolve `documentName`/`versionNumber` - a small, bounded cost
  (top-K is single digits) in exchange for not duplicating Document-level facts.

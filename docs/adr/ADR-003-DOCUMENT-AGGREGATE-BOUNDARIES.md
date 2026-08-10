# ADR-003: Document Aggregate Boundaries

## Status

Accepted — FASE 3.

## DDD building blocks used in this decision

- **Value Object**: an immutable type with no identity of its own, defined entirely by its
  attributes, interchangeable with any other instance holding equal values (e.g.
  `ContentHash`, `VersionNumber`, `EffectivePeriod`, `ChunkIndex`). Two value objects with
  equal fields are equal, full stop — there is no "the same `EffectivePeriod`, mutated" concept.
- **Entity**: a type with a persistent identity that survives changes to its attributes (e.g.
  `DocumentVersion`, identified by `DocumentVersionId`, whose `status` mutates over its
  lifecycle). Equality is identity-based, not value-based.
- **Aggregate Root**: an Entity that is the sole entry point into a cluster of objects (its
  aggregate) whose invariants must hold together at every point where the cluster is modified.
  Nothing outside the aggregate may hold a direct reference to, or modify, an object inside it
  except through the root. Other aggregates may only reference it by identity (its ID), never
  by object reference. An aggregate is also the unit a `Repository` loads and saves as a whole.

## Context

The brief explicitly warns against assuming `Document -> DocumentVersion -> DocumentChunk` is
a single aggregate, and requires reasoning about which objects are Aggregates, Entities and
Value Objects before writing code (see `PROJECT_DISCOVERY.md` FASE 3 scope). A `DocumentVersion`
can have thousands of `DocumentChunk`s, and starting FASE 5 each chunk gets its own embedding
and pgvector row, retrieved through similarity search — never by walking a parent object graph.

## Decision

Two aggregates, not one:

1. **`Document`** (root) owns `DocumentVersion` as an **Entity** inside its consistency
   boundary. A document typically has a handful of versions (`v1.0`, `v2.0`, `v3.2`, ...), so
   holding them all in memory to enforce cross-version invariants is cheap and correct:
   - `Document.addVersion` rejects a new version whose `VersionNumber` duplicates an existing
     one (`DuplicateVersionNumberException`).
   - `Document.addVersion` rejects a new version whose `EffectivePeriod` overlaps an existing
     one (`OverlappingEffectivePeriodException`, brief section 11: "versiones solapadas").
   - `Document.currentEffectiveVersion(Instant)` answers "which version applies right now"
     purely in memory (brief section 9), backed by `EffectivePeriod.isEffectiveAt`.

   These are invariants of the *document as a whole* — no single `DocumentVersion` can verify
   them by itself, which is precisely the textbook reason to make `Document` the aggregate
   root responsible for them, per Eric Evans' original formulation of aggregates.

2. **`DocumentChunk`** is its **own aggregate root**, related to `DocumentVersion`/`Document`
   only through `DocumentVersionId`/`DocumentId` (identity references, never object
   references). Reasons:
   - **Scale**: a version can have thousands of chunks; nothing about `Document`'s invariants
     requires seeing them all at once, so forcing them into the same aggregate would mean
     every read or write of `Document` risks loading (and locking) an unbounded collection for
     no invariant-related reason.
   - **Independent lifecycle**: a chunk's own lifecycle (created → embedded → re-indexed →
     deleted) is driven by the ingestion/embedding pipeline (FASE 4/5), not by document
     versioning. Re-embedding one chunk must never require loading or touching its sibling
     chunks, let alone the parent `Document`.
   - **Independent persistence and query pattern**: chunks will be persisted and queried
     through pgvector similarity search (FASE 5) — a query returns a ranked set of chunks
     across potentially many documents/versions. That access pattern has nothing to do with
     "load a `Document` and walk its children"; modeling chunks as an independently loadable
     aggregate is what makes that query pattern natural instead of fighting the domain model.

No aggregate currently spans `Document` and `DocumentChunk` together. If a future invariant
genuinely requires seeing them together, it will be enforced by an explicit domain service or
application-level saga, not by folding chunks back into the `Document` aggregate.

## Invariants and where they are enforced

| Invariant | Enforced by |
|---|---|
| `EffectivePeriod.effectiveTo` must not be before `effectiveFrom` | `EffectivePeriod` constructor (Value Object, brief section 8) |
| No two versions of a document may have overlapping effective periods | `Document.addVersion` (Aggregate Root, brief section 11) |
| No two versions of a document may share a `VersionNumber` | `Document.addVersion` |
| A `DocumentVersion`'s status may only follow `UPLOADED -> PROCESSING -> {PROCESSED, FAILED}`, `PROCESSED -> {EMBEDDED, FAILED}` | `DocumentStatus.canTransitionTo` + `DocumentVersion.transitionTo` (Entity, brief section 5) |
| A `ContentHash` is always a valid 64-character SHA-256 hex digest | `ContentHash` constructor |
| A `DocumentChunk`'s content is never blank; its index is never negative | `ChunkContent`, `ChunkIndex` constructors |

## Value Objects vs. Entities in this model

| Type | Kind | Why |
|---|---|---|
| `Document` | Aggregate Root (Entity) | Has identity (`DocumentId`) independent of its attributes; owns invariants across versions. |
| `DocumentVersion` | Entity | Has identity (`DocumentVersionId`) and mutable state (`status`) that must be tracked over time, but no invariant of its own requires it to be an aggregate root. |
| `DocumentChunk` | Aggregate Root (Entity) | Has identity (`DocumentChunkId`) and an independent lifecycle/query pattern (see above). |
| `DocumentId`, `DocumentVersionId`, `DocumentChunkId` | Value Object | Typed identifiers; equality is by wrapped value, not by "being the same instance". |
| `ContentHash`, `VersionNumber`, `EffectivePeriod`, `ChunkIndex`, `ChunkContent`, `ChunkMetadata`, `DocumentMetadata` | Value Object | No identity; fully defined and interchangeable by their attributes; several carry their own invariants (see table above). |
| `DocumentType`, `DocumentClassification`, `DocumentStatus` | Value Object (enum) | Closed, named sets of values. |

## Future relationship to persistence and pgvector

- `Document` (with its `DocumentVersion` entities) is persisted and loaded as one unit through
  the `DocumentRepository` outbound port (FASE 3, interface only — implemented by a PostgreSQL
  adapter in FASE 4, backed by `documents`/`document_versions` tables per the migration
  sequence in `docs/adr/ADR-002-MODULAR-MONOLITH.md`'s referenced brief section 36 example).
- `DocumentChunk` will get its own outbound port (a `DocumentChunkRepository` or equivalent,
  deliberately **not created yet** — there is no caller for it until chunking exists in
  FASE 4, and adding it now would be a premature abstraction per brief section 51/58) backed by
  a pgvector-based adapter in FASE 5, storing one row per chunk keyed by `DocumentChunkId`,
  carrying `documentId`/`documentVersionId` as plain columns for filtering and its embedding
  vector for similarity search. Retrieval will query that table/index directly — it will never
  load a `Document` aggregate to reach its chunks.

## Consequences

- Positive: `Document` stays small and fast to load/save regardless of how much content a
  version has; chunk-heavy operations (chunking, embedding, re-indexing, vector search) scale
  independently of document/version bookkeeping.
- Positive: the model directly demonstrates, in code, the answer to "how do you keep a
  document-versioning domain honest while still being able to serve vector search at scale" —
  useful when defending this architecture to reviewers (brief section 57).
- Cost: `DocumentChunk` cannot rely on `Document`/`DocumentVersion` to validate that the
  version it references actually exists — that consistency is eventual, enforced by the
  application layer/use cases that create chunks (FASE 4), not by the domain model itself. This
  is the standard, accepted trade-off of cross-aggregate references in DDD.

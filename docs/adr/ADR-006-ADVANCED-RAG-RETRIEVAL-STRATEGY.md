# ADR-006: Advanced RAG Retrieval Strategy (Hybrid Search, RRF, Reranking, Query Expansion)

## Status

Accepted — FASE 6.

## Context

FASE 5 (Basic RAG) retrieves exclusively by vector cosine similarity through `VectorSearchPort`.
FASE 6 asks for Advanced RAG: hybrid retrieval combining semantic search with keyword/BM25-style
lexical search, metadata filtering, reranking and (optional) query expansion - while preserving
the FASE 5 architecture (DDD + Hexagonal, ArchUnit-enforced boundaries) and Basic RAG's exact
observable behaviour for a plain `{"question": "..."}` request.

Several concrete design questions had to be answered before writing code:

1. Does hybrid search need a new port, or can `VectorSearchPort` be extended?
2. What lexical search technology, given "no new infrastructure" (brief section 3)?
3. How are a cosine-similarity score and a full-text relevance score combined?
4. Is reranking a port (swappable/external) or plain application logic?
5. Is query expansion a port, an LLM call, or plain application logic?
6. How does metadata filtering (by `documentType`/`documentClassification`, not currently in
   `vector_store`'s projection) get pushed down to SQL without duplicating Document-level facts
   the way ADR-005 explicitly decided against?

## Decisions

### 1. Two ports, not one: `VectorSearchPort` (extended) + new `LexicalSearchPort`

`VectorSearchPort.search` gained a `RetrievalFilter` parameter (backward compatible -
`RetrievalFilter.none()` reproduces FASE 5's unfiltered behaviour exactly). A **new**
`LexicalSearchPort` was added rather than folding lexical search into `VectorSearchPort`: the two
represent genuinely different retrieval mechanisms (cosine similarity over embeddings vs.
PostgreSQL `tsvector`/`tsquery` full-text matching) with different result shapes
(`RetrievedChunk` vs. `LexicalSearchResult` - different score semantics, see decision 3) and
different failure modes. Merging them into one port/method would hide that difference behind a
single signature and make the no-answer policy (which inspects each branch's evidence
independently - see `AskInsuranceKnowledgeUseCase`) harder to express correctly.

### 2. PostgreSQL full-text search, not Elasticsearch/OpenSearch

PostgreSQL (already the vector store) supports `tsvector`/`tsquery`/GIN indexes/`ts_rank_cd`
natively. Introducing a second search engine for a PoC would mean a new Docker service, a new
adapter technology stack, a second data-sync problem (keeping Elasticsearch in sync with
`document_chunks`) - real infrastructure cost for a capability PostgreSQL already provides
adequately at this scale (brief section 3/46 explicitly rules out new infrastructure for this
phase). `PostgresLexicalSearchAdapter` reuses `vector_store`'s existing rows (a generated
`content_tsv` column, `V4__hybrid_search.sql`) rather than a separate table - the semantic and
lexical representations of a chunk are always in sync by construction, because there is still
only one write path (`PgVectorStoreAdapter#index`).

### 3. Reciprocal Rank Fusion (RRF), not raw score addition

`RetrievedChunk.similarityScore()` is cosine similarity (bounded `[-1, 1]`, in practice `[0, 1]`
for normalized embeddings). `LexicalSearchResult.lexicalScore()` is PostgreSQL's `ts_rank_cd`
output - an unbounded, corpus-and-query-dependent value with no fixed scale. Adding or averaging
them directly would be mixing two numbers with no common unit, producing a fusion score with no
defensible meaning. RRF avoids this entirely by fusing **rank position**, not raw score:

```
RRF(d) = 1 / (k + rankSemantic(d)) + 1 / (k + rankLexical(d))
```

A candidate missing from one branch contributes `0` for that term rather than a fabricated rank
or score - `ScoreFusion` preserves the original per-branch score/rank on
`HybridRetrievalResult` precisely so this is never lossy or ambiguous later (e.g. in the
no-answer policy). `k = 60` is the constant from the original RRF paper (Cormack, Clarke &
Buettcher, 2009) - a reasonable, citable PoC starting point, explicitly not re-derived or tuned
against this project's data (`insurance-ai.rag.hybrid.rrf-k` is configurable for future
calibration, matching FASE 10's role for `insurance-ai.rag.semantic.similarity-threshold`). Ties
in fusion score are broken by ascending `chunkId` string - arbitrary but deterministic, which
matters for reproducible tests.

### 4. Reranking is a port; the initial implementation is a documented PoC heuristic

`RerankerPort` exists (rather than a plain class) because it is explicitly designed to be
replaced by a real model call later (brief section 12) - a cross-encoder reranker is external
infrastructure with real latency/cost, exactly the kind of dependency a port exists to abstract.
The FASE 6 implementation, `RuleBasedReranker`, is a small, deterministic keyword-coverage boost
on top of the fusion score - explicitly **not** presented as ML-quality (brief section 11; see
`docs/rag/RERANKING.md`). `AskInsuranceKnowledgeUseCase`'s no-answer policy never treats
`rerankerScore` as grounding evidence (only `semanticScore`/`lexicalScore` are inspected) so a
future swap to a real reranker changes ranking quality only, never grounding correctness.

### 5. Query expansion is plain application logic, not a port, and never calls an LLM

`QueryExpander` is a small, deterministic, disabled-by-default class using a hardcoded, explicitly
non-authoritative synonym map (brief section 13: not a legal/insurance-reviewed thesaurus).
Not a port: there is exactly one implementation and no current external dependency to abstract
over - promoting it to a port before a second implementation exists would be speculative
(matching the brief's explicit "no overengineering" instruction). Not LLM-backed, deliberately
(brief section 14): an extra model round-trip per question adds cost, latency, a second
prompt-injection surface, and a second point of failure for a capability whose value here (a
few extra keyword synonyms for the lexical branch) does not justify it. Expanded terms feed only
the lexical query (via `websearch_to_tsquery`'s ` OR` support) - never the semantic embedding
call, since real embeddings already generalize past exact wording and stuffing extra synonym
tokens into the embedded string risks distorting the vector rather than helping it.

### 6. `documentType`/`documentClassification` denormalized into `vector_store.metadata`

ADR-005 deliberately did **not** duplicate Document-level facts (`documentName`, `versionNumber`)
into `vector_store`, resolving them via a `DocumentRepository` lookup per unique `documentId` in
a result set instead. FASE 6 makes one narrow, explicit exception: `documentType` and
`documentClassification` (two small enum values, immutable for a `Document`'s lifetime - no
staleness risk, unlike a document's display name) are now written into `vector_store.metadata` at
index time (`VectorIndexPort#index`'s two new parameters). This is necessary because metadata
filtering must happen inside each retrieval branch's own SQL `WHERE` clause (both
`PgVectorStoreAdapter` and `PostgresLexicalSearchAdapter`, via the shared
`adapters.shared.persistence.RetrievalFilterSql`) - filtering *after* loading every candidate's
parent `Document` from `DocumentRepository` would defeat the purpose of filtering before context
assembly (brief section 6) and would be far less efficient. `documentName`/`versionNumber`
remain resolved fresh for citations, unchanged from FASE 5.

## Consequences

- Positive: `AskInsuranceKnowledgeUseCase` still knows nothing about Spring AI, PostgreSQL,
  `tsvector`, or any vendor SDK - it depends on `HybridRetrievalService`, which itself depends
  only on ports and plain application collaborators (`ScoreFusion`/`QueryExpander`/
  `ContextSelector`), verified by `ArchitectureTest`.
- Positive: a plain `{"question": "..."}` request still produces `RagAnswer` end to end
  unchanged in shape; filters are additive and optional (`ChatFilterRequest`).
- Cost: `VectorIndexPort`/`VectorSearchPort` signatures changed (backward-incompatible at the
  Java level, though `RetrievalFilter.none()` reproduces old behaviour exactly) - accepted as a
  deliberate, documented evolution rather than introducing a parallel "v2" port.
- Cost: reranking and query expansion each add one more configuration surface
  (`insurance-ai.rag.reranking`/`insurance-ai.rag.query-expansion`) - mitigated by keeping both
  toggleable and documenting their PoC-level limitations explicitly rather than silently.
- Known limitation carried forward: none of `insurance-ai.rag.hybrid.rrf-k`,
  `candidate-pool-size`, `final-top-k`, or the keyword-coverage boost weight in
  `RuleBasedReranker` are scientifically tuned - same status as FASE 5's similarity threshold,
  same intended resolution (FASE 10, Evaluation).

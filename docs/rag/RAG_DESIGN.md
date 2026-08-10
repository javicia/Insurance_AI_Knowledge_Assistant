# RAG Design — Basic RAG (FASE 5) + Advanced RAG (FASE 6)

Status: living document. This document covers the parts of the pipeline that predate and remain
unchanged since FASE 5 (embedding, vector store implementation, prompt/security boundary,
citations). **Hybrid search, RRF fusion, reranking, query expansion and metadata filtering are
FASE 6 - see `docs/rag/HYBRID_SEARCH.md`, `docs/rag/RERANKING.md` and
`docs/adr/ADR-006-ADVANCED-RAG-RETRIEVAL-STRATEGY.md`.** The full Prompt Registry remains FASE 9,
out of scope here.

## 1. Flow

FASE 5's flow (below) is what `VectorSearchPort`/`EmbeddingModelPort` still implement; as of
FASE 6, `AskInsuranceKnowledgeUseCase` no longer calls them directly - it delegates to
`HybridRetrievalService`, which runs this semantic step *and* the lexical/fusion/reranking
pipeline together. See `docs/rag/HYBRID_SEARCH.md` section 1 for the current, complete flow
diagram; what follows here is still accurate for the semantic branch specifically.

```
Question
   |
   v
EmbeddingModelPort.embed(question)          -- HybridRetrievalService (FASE 5: AskInsuranceKnowledgeUseCase directly)
   |
   v
VectorSearchPort.search(vector, topK, threshold, filter)     -- filter param added FASE 6
   |
   v
(FASE 6: fused with LexicalSearchPort results, reranked, context-selected - see HYBRID_SEARCH.md)
   |
   v
Empty final candidate list? --yes--> RagAnswer.noAnswer()  (LLM is never called - brief section 11)
   |no
   v
Build SourceReference list (DocumentRepository, one lookup per unique documentId)
   |
   v
LlmPrompt(systemInstructions, question, retrievedContextPassages)
   |
   v
LlmProvider.complete(prompt)                -- OpenAiLlmAdapter / AnthropicLlmAdapter / FakeLlmAdapter
   |
   v
RagAnswer(answer, sources, grounding=GROUNDED, traceId)
```

Entry point: `POST /api/chat` (`ChatController`) -> `AskInsuranceKnowledgeUseCase` ->
`HybridRetrievalService`. The use case's dependencies are `HybridRetrievalService`,
`LlmProvider`, `DocumentRepository` and `InsuranceAiProperties` - no Spring AI type, no vendor
SDK, no JDBC, no HTTP (brief section 6). `InsuranceAiProperties` lives in
`application.configuration`, not `infrastructure` - see its Javadoc.

## 2. Ingestion side: how a chunk becomes searchable

FASE 4 already produces `DocumentChunk` rows in `document_chunks` once a version reaches
`PROCESSED`. FASE 5 adds the next pipeline stage:

```
insurance.document.processed (Kafka)
   |
   v
DocumentProcessedEventListener -> EmbedDocumentVersionUseCase
   |
   v
for each DocumentChunk (DocumentChunkRepository.findByDocumentVersionId):
   EmbeddingModelPort.embed(chunk.content()) -> EmbeddingVector
   VectorIndexPort.index(chunk, vector, descriptor)   -- PgVectorStoreAdapter, writes vector_store
   |
   v
DocumentVersion.markEmbedded() -> status EMBEDDED
   |
   v
insurance.document.embedded (Kafka) -- the first phase this topic is actually produced/consumed;
                                        FASE 4 named it but deliberately left it unused.
```

Idempotency and failure handling follow the same policy as FASE 4's
`ProcessDocumentVersionUseCase` - see `docs/adr/ADR-004-DOCUMENT-PROCESSING-FAILURE-POLICY.md`
(now applied to embedding too: a redelivered event for an already-`EMBEDDED`/`FAILED` version
is a no-op; a version with zero chunks is marked `FAILED`, never silently `EMBEDDED`).

## 3. Vector store implementation

`PgVectorStoreAdapter` implements both `VectorSearchPort` and `VectorIndexPort` against the
`vector_store` table (`V3__vector_store.sql`). It uses plain JDBC rather than calling Spring
AI's `PgVectorStore.add()`/`similaritySearch()` directly - **not because Spring AI isn't
used**, but because those convenience methods always recompute the embedding themselves from
raw text via their own internally-configured `EmbeddingModel`, with no supported way to accept
a caller-supplied vector. That is incompatible with two hard requirements of this phase: the
use case must perform "question -> embedding -> vector search" as separate, explicit steps
through `EmbeddingModelPort` (brief section 6), and an embedding must never be computed twice
for the same operation (cost and latency). The adapter instead reuses Spring AI's own schema
constants (`PgVectorStore.DEFAULT_TABLE_NAME`/`DEFAULT_SCHEMA_NAME`), its cosine-distance SQL
template (`PgVectorStore.PgDistanceType.COSINE_DISTANCE.similaritySearchSqlTemplate`), and the
same `com.pgvector:pgvector` JDBC type (`PGvector`) it depends on - so the table remains fully
compatible with `PgVectorStore`'s own conventions even though the high-level API isn't called.
Full reasoning in the class Javadoc.

```
Application
    |
    v
VectorSearchPort / VectorIndexPort
    |
    v
PgVectorStoreAdapter
    |
    v
JdbcTemplate + com.pgvector:pgvector (PGvector) + PgVectorStore's own SQL/schema constants
    |
    v
PostgreSQL + pgvector (vector_store table, V3__vector_store.sql)
```

`PgVectorStore` never appears in `domain` or `application` - verified by `ArchitectureTest`.

## 4. Similarity metric, top-K, threshold

- **Metric**: cosine distance (pgvector `<=>` operator, `vector_cosine_ops` HNSW index) -
  Spring AI's default, and the metric OpenAI's own embeddings are designed for.
- **top-K**: `insurance-ai.rag.semantic.top-k` (default `8`).
- **similarity-threshold**: `insurance-ai.rag.semantic.similarity-threshold` (default `0.75`).

(FASE 6 nested these under `semantic.*` alongside the new `lexical.*`/`hybrid.*`/`reranking.*`/
`query-expansion.*`/`context.*` sections - see `InsuranceAiProperties.Rag` and
`docs/rag/HYBRID_SEARCH.md`. Values and meaning are otherwise unchanged from FASE 5.)

Both are **initial PoC parameters, not scientifically tuned values** - brief section 12
explicitly requires saying so plainly: they were chosen as reasonable starting points, not
derived from measurement. They are exactly what FASE 10 (Evaluation) exists to calibrate
against real relevance/faithfulness metrics on the evaluation dataset built there.

**Production similarity distribution vs. test fake similarity distribution:** `0.75` is
calibrated against OpenAI's real semantic embeddings, not against `FakeEmbeddingModelAdapter`'s
bag-of-words hashing (`docs/rag/EMBEDDINGS.md`). The two are not interchangeable: a clearly
on-topic policy passage/question pair (e.g. "Water damage caused by a burst pipe is covered up
to the policy limit of 5000 EUR." vs. "Is water damage from a burst pipe covered?") only reaches
approximately cosine similarity `0.60` under the fake adapter, because word-overlap counting has
nothing to do with the semantic distribution real embeddings produce. The test suite's shared
`src/test/resources/application-test.yaml` (not a per-class override - see
`docs/testing/TESTCONTAINERS.md` for why) lowers `insurance-ai.rag.semantic.similarity-threshold`
to `0.5` for the whole test profile, so `RagPipelineIntegrationTest` can exercise the pipeline
mechanics (retrieval -> grounding -> LLM call) end to end with the offline fake provider.
`application.yaml`'s production default stays at `0.75` and is never touched to accommodate the
fake provider. As of FASE 6, this override is a belt-and-braces measure rather than strictly
necessary: a genuine PostgreSQL full-text match on the lexical branch alone is independently
sufficient for grounding (see `docs/rag/HYBRID_SEARCH.md` section 7).

## 5. No-answer strategy

FASE 5: if `VectorSearchPort.search` returned an empty list (no chunk cleared the similarity
threshold), `AskInsuranceKnowledgeUseCase` returned `RagAnswer.noAnswer(...)` immediately and
**never called `LlmProvider`**. FASE 6 generalizes this to two independent retrieval branches -
see `docs/rag/HYBRID_SEARCH.md` section 7 for the exact policy - but the principle is identical:
an actual code path skips the LLM call entirely when no branch found sufficient evidence (brief
section 11), never a prompt instruction asking the model to decline. `temperature` is not used
as a hallucination control anywhere in this codebase.

`retrievalScore` (the `similarityScore` on `RetrievedChunk`/`semanticScore` on
`HybridRetrievalResult`, sourced directly from pgvector's distance calculation) and "answer
confidence" are deliberately never conflated: this PoC exposes no invented "confidence" number
for an LLM's answer (brief section 12) - grounding is represented only as the binary
`GroundingStatus.GROUNDED`/`NOT_GROUNDED` shown in the response. FASE 6's `fusionScore`/
`rerankerScore` are treated the same way - never surfaced as a confidence number, never used as
grounding evidence on their own (see `docs/rag/RERANKING.md` section 3).

## 6. Prompt and security boundary

`InsuranceRagSystemPrompt` (`application.rag`, version `insurance-rag-system-prompt-v1.0`) is a
single versioned constant - not the full Prompt Registry FASE 9 builds (database-backed,
audited, multiple prompts). Its text explicitly states retrieved documents are untrusted data,
must never be treated as instructions, and that the system informs rather than decides (brief
section 13, mirroring the AI Governance stance already recorded architecturally since FASE 0).

The system/user boundary is enforced structurally, not just by prompt wording:
`LlmPrompt(systemInstructions, userQuestion, retrievedContextPassages)` keeps these fields
separate all the way to the vendor call - `OpenAiLlmAdapter`/`AnthropicLlmAdapter` send
`systemInstructions` as a `SystemMessage` and the question+retrieved passages as a `UserMessage`
with explicit `--- Retrieved context ---` delimiters (`LlmMessageFormatter`), so untrusted
content can never silently merge into the trusted instruction channel. Full prompt-injection
defenses (detection, blocking, audit) are FASE 8 - this phase only establishes the boundary the
later framework will harden.

## 7. Citations

`SourceReference` is built directly from data already on the `Document`/`DocumentVersion`
aggregates and the `RetrievedChunk` (itself sourced from `document_chunks` structural metadata,
propagated through `vector_store`) - nothing is inferred. `document`/`version` come from a
`DocumentRepository` lookup per unique `documentId` in the result set (never duplicated into
`vector_store`, see `docs/adr/ADR-005-EMBEDDING-AS-DERIVED-PROJECTION.md`); `page`/`section`/
`chunkId` come straight from the retrieved chunk.

## 8. Current limitations

- ~~Single-query retrieval only: no hybrid (keyword) search, no query expansion, no reranking~~ -
  implemented in FASE 6, see `docs/rag/HYBRID_SEARCH.md`/`docs/rag/RERANKING.md` for what was
  built and their own "current limitations" sections for what remains PoC-level within them.
- No AI Audit trail entries are written yet for chat requests (FASE 9) - `traceId` is present
  in every response and every log line (`TraceIdFilter`, FASE 1) so requests are already
  correlatable, and FASE 6 adds `RetrievalDiagnostics` (logged, not yet persisted) as a step
  toward this, but nothing persists retrieval scores/token counts durably yet.
- No PII/prompt-injection guardrail framework yet (FASE 8) - only the structural
  system/user separation described above.
- Embeddings always use OpenAI regardless of the configured chat provider - see
  `docs/rag/EMBEDDINGS.md`.
- No Maximal Marginal Relevance / full document-diversity optimization in context selection
  (FASE 6 uses a simple per-section cap instead - see `docs/rag/HYBRID_SEARCH.md` section 6).

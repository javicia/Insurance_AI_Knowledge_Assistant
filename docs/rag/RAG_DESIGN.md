# RAG Design — Basic RAG (FASE 5)

Status: living document. Hybrid search, reranking, query expansion and the full Prompt
Registry are FASE 6+ / FASE 9 - explicitly out of scope here.

## 1. Flow

```
Question
   |
   v
EmbeddingModelPort.embed(question)          -- AskInsuranceKnowledgeUseCase
   |
   v
VectorSearchPort.search(vector, topK, threshold)
   |
   v
Empty? --yes--> RagAnswer.noAnswer()  (LLM is never called - brief section 11)
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

Entry point: `POST /api/chat` (`ChatController`) -> `AskInsuranceKnowledgeUseCase`. The use
case's only dependencies are `EmbeddingModelPort`, `VectorSearchPort`, `LlmProvider` and
`DocumentRepository` - no Spring AI type, no vendor SDK, no JDBC, no HTTP (brief section 6).

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
- **top-K**: `insurance-ai.rag.top-k` (default `8`).
- **similarity-threshold**: `insurance-ai.rag.similarity-threshold` (default `0.75`).

Both are **initial PoC parameters, not scientifically tuned values** - brief section 12
explicitly requires saying so plainly: they were chosen as reasonable starting points, not
derived from measurement. They are exactly what FASE 10 (Evaluation) exists to calibrate
against real relevance/faithfulness metrics on the evaluation dataset built there.

## 5. No-answer strategy

If `VectorSearchPort.search` returns an empty list (no chunk cleared the similarity threshold),
`AskInsuranceKnowledgeUseCase` returns `RagAnswer.noAnswer(...)` immediately and **never calls
`LlmProvider`** - not a prompt instruction asking the model to decline, an actual code path
that skips the call entirely (brief section 11). `temperature` is not used as a hallucination
control anywhere in this codebase.

`retrievalScore` (the `similarityScore` on `RetrievedChunk`, sourced directly from pgvector's
distance calculation) and "answer confidence" are deliberately never conflated: this PoC
exposes no invented "confidence" number for an LLM's answer (brief section 12) - grounding is
represented only as the binary `GroundingStatus.GROUNDED`/`NOT_GROUNDED` shown in the response.

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

- Single-query retrieval only: no hybrid (keyword) search, no query expansion, no reranking -
  FASE 6.
- No AI Audit trail entries are written yet for chat requests (FASE 9) - `traceId` is present
  in every response and every log line (`TraceIdFilter`, FASE 1) so requests are already
  correlatable, but nothing persists retrieval scores/token counts durably yet.
- No PII/prompt-injection guardrail framework yet (FASE 8) - only the structural
  system/user separation described above.
- Embeddings always use OpenAI regardless of the configured chat provider - see
  `docs/rag/EMBEDDINGS.md`.

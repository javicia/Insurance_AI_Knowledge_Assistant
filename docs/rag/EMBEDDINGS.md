# Embeddings

Status: FASE 5 (Basic RAG).

## Model in use

| Property | Value |
|---|---|
| Provider | OpenAI |
| Model | `text-embedding-3-small` |
| Dimensions | 1536 |
| Similarity metric | Cosine |

Configured explicitly in `application.yaml` (`spring.ai.openai.embedding.model`) rather than
relying on Spring AI's undocumented internal default - brief section 2 explicitly requires not
hardcoding a dimension without verifying it matches the model actually in use. 1536 was
verified two ways: it is OpenAI's own published dimension for `text-embedding-3-small`, and it
matches `PgVectorStore.OPENAI_EMBEDDING_DIMENSION_SIZE` (Spring AI's own fallback constant),
cross-checked while inspecting the `spring-ai-pgvector-store` sources during this phase.

`V3__vector_store.sql`'s `embedding vector(1536)` column width is hard-tied to this model. If
the model changes to one with a different dimension, that column must be recreated (a pgvector
column has one fixed width) - see "Reindexing" below.

## Why embeddings always use OpenAI, even when the chat provider is Anthropic

Anthropic has no embeddings API/product. `insurance-ai.ai.provider` (brief section 8) switches
**chat completion** between OpenAI and Anthropic (`OpenAiLlmAdapter` /
`AnthropicLlmAdapter`, mutually exclusive via `@ConditionalOnProperty`), but
`OpenAiEmbeddingModelAdapter` is active for both `openai` and `anthropic` (excluded only when
`provider: fake`) - see its Javadoc. This is a real technical constraint of the provider
landscape, not an architectural shortcut, and it is the reason FASE 1's original TEMPORARY
placeholder-credential note could not be resolved into "OpenAI is never required" - it can be
resolved into "OpenAI is never required *for chat*", which is what actually happened.

## Cost/limitation notes (PoC-level, brief section 17)

- No embedding caching: identical text re-embeds if re-processed (mitigated by FASE 4's
  content-hash idempotency, which prevents the same PDF from being processed twice at all).
  A duplicate-text cache is a reasonable future optimization, not implemented here.
- No batching: `EmbedDocumentVersionUseCase` embeds one chunk at a time via
  `EmbeddingModelPort.embed(String)`. Fine for PoC-scale document volumes; a production system
  ingesting large corpora would batch multiple chunks per API call.
- No rate-limit/backoff tuning beyond what Spring AI's OpenAI client does by default.

## Provider abstraction

`EmbeddingModelPort` (`ports.outbound`) is the only type the application layer depends on:

```
Application (EmbedDocumentVersionUseCase, indirectly via VectorIndexPort's stored descriptor)
    |
    v
EmbeddingModelPort
    |
    v
OpenAiEmbeddingModelAdapter  (real, wraps Spring AI's EmbeddingModel)
FakeEmbeddingModelAdapter    (deterministic, offline - see below)
```

Neither `AskInsuranceKnowledgeUseCase` nor `EmbedDocumentVersionUseCase` imports Spring AI's
`EmbeddingModel` type, or any OpenAI SDK type, directly.

### The fake adapter

`FakeEmbeddingModelAdapter` (`insurance-ai.ai.provider: fake`) is a deterministic
feature-hashing bag-of-words vectorizer: each word in the input hashes into one of 1536 buckets
(matching `vector_store.embedding`'s fixed `vector(1536)` column width - pgvector rejects any
vector whose dimension does not match the column, regardless of which "model" produced it),
bucket counts are L2-normalized. It is **not a real embedding model** - it exists solely so the
FASE 5 pipeline can be exercised end to end (automated tests and manual validation) without
OpenAI credentials (brief section 17/18). It produces vectors that are closer together for
texts sharing vocabulary and farther apart otherwise, which is enough to prove retrieval
mechanics (relevant vs. irrelevant chunk ranking) - never enough to prove real semantic
understanding, and never presented as such. See `FakeEmbeddingModelAdapterTest` for what is
actually verified about it.

## Versioning and reindexing strategy

Every vector stored in `vector_store` carries, in its `metadata` JSON column,
`embeddingModel`, `embeddingModelVersion` (OpenAI does not expose a separate version string
beyond the model name itself, so this is currently always `null` for the OpenAI adapter) and
`embeddingDimension`, sourced from `EmbeddingModelPort#descriptor()` at indexing time (see
`PgVectorStoreAdapter#index`). This is what lets a future process detect drift between what a
vector was embedded with and what is currently configured.

**To reindex after an embedding model change:**

1. If the new model has the **same dimension** (e.g. switching between two 1536-dimension
   OpenAI models): re-run `EmbedDocumentVersionUseCase` for every `EMBEDDED` `DocumentVersion`,
   reading chunk text from `document_chunks` (never from `vector_store`, which is a derived
   projection - see `docs/adr/ADR-005-EMBEDDING-AS-DERIVED-PROJECTION.md`). Because indexing
   upserts on the chunk's own id (`ON CONFLICT (id) DO UPDATE`), this replaces each vector in
   place with no separate delete step.
2. If the new model has a **different dimension**, the `vector_store.embedding` column's fixed
   width must change first: create a new Flyway migration that (a) drops
   `spring_ai_vector_index`, (b) alters the column to the new `vector(n)` width (or, more
   safely, creates a new table and swaps it), then re-run step 1 for every version. This is a
   real schema migration and is intentionally not automated in FASE 5 - no scheduled
   reindexing job exists yet; the process above is documented, not implemented, matching brief
   section 5's explicit instruction.

## Tests

- `FakeEmbeddingModelAdapterTest` - determinism, dimension, similarity ordering (unit).
- `OpenAiEmbeddingModelAdapterTest` - delegation and descriptor reporting against a mocked
  Spring AI `EmbeddingModel` (unit, no real API call).
- `PgVectorStoreAdapterTest` - real PostgreSQL + pgvector via Testcontainers: indexing,
  similarity search, threshold rejection, top-K, metadata propagation, re-index upsert
  (integration).

No test in the automated suite calls the real OpenAI embeddings API - see the FASE 5 report for
what manual end-to-end validation did and did not cover with real credentials.

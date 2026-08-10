# Hybrid Search (FASE 6, Advanced RAG)

Status: living document. Builds on `docs/rag/RAG_DESIGN.md` (Basic RAG, FASE 5) - read that
first. See `docs/adr/ADR-006-ADVANCED-RAG-RETRIEVAL-STRATEGY.md` for the architectural reasoning
behind every decision summarized here.

## 1. Pipeline

```
Question
   |
   v
QueryExpander.expand(question)            -- optional, disabled by default, deterministic
   |                                          (adds terms to the lexical query only)
   +----------------------+
   |                      |
   v                      v
EmbeddingModelPort    lexicalQuery = question [+ " OR " + expanded terms]
   |                      |
   v                      v
VectorSearchPort      LexicalSearchPort           -- run concurrently (CompletableFuture)
   |                      |
   v                      v
List<RetrievedChunk>  List<LexicalSearchResult>
   |                      |
   +----------+-----------+
              v
       ScoreFusion.fuse (Reciprocal Rank Fusion)
              v
       List<HybridRetrievalResult>  (fusionScore always set; semantic/lexical fields nullable)
              v
       candidate pool = top insurance-ai.rag.hybrid.candidate-pool-size
              v
       RerankerPort.rerank (if insurance-ai.rag.reranking.enabled)
              v
       final ranked list = top insurance-ai.rag.hybrid.final-top-k
              v
       ContextSelector.select (dedup, per-section diversity cap, character budget)
              v
       List<HybridRetrievalResult> finalCandidates
              v
       AskInsuranceKnowledgeUseCase: qualifying candidate present?
          /                                    \
        no                                     yes
         |                                      |
         v                                      v
     RagAnswer.noAnswer()                  LlmProvider.complete(...)  -> RagAnswer(GROUNDED, ...)
```

`HybridRetrievalService.retrieve` is the single orchestrator (used by
`AskInsuranceKnowledgeUseCase`, which owns only the no-answer decision, citation building and LLM
invocation - see brief section 17 and the class Javadocs for why these are split).

## 2. Semantic search

Unchanged from FASE 5: `EmbeddingModelPort` + `VectorSearchPort`, cosine similarity,
`insurance-ai.rag.semantic.top-k`/`insurance-ai.rag.semantic.similarity-threshold` (production
default `0.75`, untouched by FASE 6). Now additionally accepts a `RetrievalFilter`
(`RetrievalFilter.none()` reproduces the old unfiltered behaviour exactly).

## 3. Lexical search (PostgreSQL full-text search)

`PostgresLexicalSearchAdapter` queries `vector_store.content_tsv`, a `GENERATED ALWAYS ... STORED`
`tsvector` column added by `V4__hybrid_search.sql`:

```sql
setweight(to_tsvector('english', coalesce(content, '')), 'A') ||
setweight(to_tsvector('english', coalesce(metadata ->> 'section', '')), 'B')
```

- **Configuration**: `'english'` text search configuration (stemming + English stop-word
  removal) - a documented PoC choice, not scientifically tuned for insurance-domain
  terminology (defined/legal terms, abbreviations, product names may stem oddly; this is exactly
  what FASE 10 exists to evaluate and potentially replace with a custom configuration/dictionary).
- **Weighting**: chunk content is weight `A` (primary signal), the chunk's structural `section`
  heading is weight `B` (secondary signal) - a simple, explainable two-tier weighting, not a
  tuned ranking function.
- **Ranking function**: `ts_rank_cd` (cover density ranking - rewards query terms appearing close
  together, not just present) via `websearch_to_tsquery('english', ?)`, which accepts natural-
  language input directly (handles quoted phrases, `OR`, `-exclusion`) and sanitizes it
  internally, so query-expansion output (`"question OR synonym1 OR synonym2"`) never needs manual
  `tsquery` operator escaping.
- **Index**: a single GIN index on `content_tsv` (`vector_store_content_tsv_gin_index`) -
  appropriate at this PoC's data scale; no partitioning/multi-index strategy considered.
- **Relevance floor (FASE 14 audit remediation)**: PostgreSQL's `@@` match operator is the first
  gate - a row is either returned (genuinely matched the query) or it is not - but "matched at
  all" and "matched well" are different things: `@@` is satisfied by a single weak keyword overlap
  just as readily as a strong multi-term match. `insurance-ai.rag.lexical.min-rank` (default
  `0.0`, i.e. no behaviour change from the original FASE 6 design until tuned) adds a second gate
  on the actual `ts_rank_cd` value. See `AskInsuranceKnowledgeUseCase`'s no-answer policy and
  `docs/adr/ADR-012-AUDIT-REMEDIATION.md`.

Verified empirically during this project (not assumed): `to_tsvector('english', 'covers')` and
`to_tsquery('english', 'covered')` both yield the `cover` lexeme (shared verb stem); a
noun-derived form like `coverage` stems to a distinct `coverag` lexeme and is *not* matched by
`covered` - see `PostgresLexicalSearchAdapterTest.findsAChunkByAStemmedPartialMatch`'s Javadoc.

## 4. Score fusion (Reciprocal Rank Fusion)

See ADR-006 decision 3 for the full rationale. `insurance-ai.rag.hybrid.rrf-k` (default `60`,
the original RRF paper's value) controls how much weight low ranks retain; higher `k` flattens
the curve (ranks matter less relative to each other), lower `k` sharpens it (top ranks dominate
more). `ScoreFusion` is pure computation (no I/O), unit-tested directly (`ScoreFusionTest`) for:
both-branches-present summation, single-branch-only partial scoring, descending order, and
deterministic tie-breaking.

## 5. Metadata filtering

`RetrievalFilter` (`documentId`, `documentVersionId`, `documentType`, `documentClassification`,
`page`, `section` - all independently nullable) is translated into `AND metadata ->> 'field' = ?`
SQL conditions by the shared `adapters.shared.persistence.RetrievalFilterSql`, used identically
by both `PgVectorStoreAdapter.search` and `PostgresLexicalSearchAdapter.search` - so a filter
restricts both retrieval branches consistently before fusion, not after. See ADR-006 decision 6
for why `documentType`/`documentClassification` are now denormalized into `vector_store.metadata`
to make this possible.

`POST /api/chat`'s optional `filters` field (`ChatFilterRequest`) maps 1:1 onto
`RetrievalFilter`; omitting it entirely (or sending a plain `{"question": "..."}` body) is
`RetrievalFilter.none()` - no restriction, byte-for-byte FASE 5 behaviour.

## 6. Candidate pooling, reranking, context selection

- **Candidate pool**: `insurance-ai.rag.hybrid.candidate-pool-size` (default `20`) - the fused
  list is truncated to this size *before* reranking, so reranking never has to score an unbounded
  candidate set.
- **Reranking**: see `docs/rag/RERANKING.md`. Toggleable via
  `insurance-ai.rag.reranking.enabled` (default `true`); when disabled, the pool is simply
  truncated to `insurance-ai.rag.hybrid.final-top-k` in fusion order.
- **Context selection** (`ContextSelector`): deduplicates by `chunkId` (defensive - fusion
  already deduplicates by construction), caps chunks per `(documentId, section)` pair at 3 (a
  simple diversity guard, not full Maximal Marginal Relevance - deliberately deferred, brief
  section 20), and enforces `insurance-ai.rag.context.max-characters` (default `6000`) - a PoC
  approximation of a context window budget (character count, not real token counting; a future
  phase could swap in a real tokenizer-based budget without changing `ContextSelector`'s
  interface). At least one candidate is always kept even if it alone exceeds the budget, so a
  single oversized chunk never produces an empty context.

## 7. No-answer policy

See `AskInsuranceKnowledgeUseCase`'s Javadoc for the authoritative statement. Summary: a final
candidate qualifies as grounding evidence if `semanticScore >= insurance-ai.rag.semantic.
similarity-threshold` (FASE 5's original criterion) **or** it has a non-null `lexicalScore` that
also clears `insurance-ai.rag.lexical.min-rank` (FASE 14 audit remediation - defaults to `0.0`,
reproducing the original FASE 6 "PostgreSQL's own match predicate is the bar" behaviour exactly;
see `docs/adr/ADR-012-AUDIT-REMEDIATION.md`). Neither `fusionScore` nor `rerankerScore` is ever
used as grounding evidence - see ADR-006 decision 4 for why the reranker specifically must never be
mistaken for proof of grounding.

## 8. Partial failure / graceful degradation

Semantic and lexical retrieval run concurrently (`CompletableFuture`, common pool - brief section
28 explicitly discourages a custom executor/reactive stack for this). If one branch's future
fails, retrieval degrades to the other branch alone (`RetrievalOutcome.SEMANTIC_ONLY`/
`LEXICAL_ONLY`) and proceeds; if **both** fail, `HybridRetrievalService` throws
`TransientProcessingException` rather than silently returning an empty/no-answer result - a real
infrastructure outage must surface as an error (mapped to a 5xx by `GlobalExceptionHandler`), not
be reinterpreted as "no relevant documentation found" (a valid, very different, 200 OK outcome).

## 9. Retrieval diagnostics

`RetrievalDiagnostics` (outcome, semantic/lexical/fused/reranked/final candidate counts) is
logged at `INFO` on every request (`HybridRetrievalService`) - not exposed over `POST /api/chat`
by default. Kept as an explicit, well-defined type specifically so a future AI Audit/Evaluation
persistence step (FASE 9/10) has something ready to record rather than needing to reconstruct
this after the fact.

## 10. Backward compatibility with Basic RAG

A plain `{"question": "..."}` request produces the exact same `RagAnswer` shape as FASE 5 -
verified by `RagPipelineIntegrationTest`, unchanged in intent, now running through the full
hybrid pipeline rather than semantic-only retrieval. `VectorSearchPort`'s cosine-similarity
semantic branch is untouched; hybrid search only *adds* a second, independent grounding path.

## 11. Current limitations

- `rrf-k`, `candidate-pool-size`, `final-top-k`, and `RuleBasedReranker`'s keyword-coverage boost
  weight are PoC starting points, not scientifically tuned (see ADR-006).
- `'english'` full-text search configuration is not tuned for insurance-domain terminology.
- `QueryExpander`'s synonym map is small and illustrative, not a reviewed insurance thesaurus.
- No Maximal Marginal Relevance / full document-diversity optimization (a simple per-section cap
  is used instead).
- `RetrievalDiagnostics` is logged but not yet persisted for audit/evaluation (FASE 9/10).

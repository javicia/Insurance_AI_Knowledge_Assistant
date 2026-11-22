# Hybrid Search (FASE 6, Advanced RAG)

> **FASE 28 supersedes parts of this document.** The lexical branch no longer uses the
> `english` text search configuration or `websearch_to_tsquery`'s AND-semantics, and lexical
> grounding now additionally requires term coverage, not just `ts_rank_cd >= min-rank`. The
> pipeline diagram below is also outdated in one detail: the lexical query is no longer joined
> with a literal `" OR "`. Read **`docs/rag/AUDITORIA_RAG_ES.md`** for the current behaviour,
> the measurements behind it, and why rank alone cannot gate grounding.

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
  just as readily as a strong multi-term match. `insurance-ai.rag.lexical.min-rank` adds a second
  gate on the actual `ts_rank_cd` value. Calibrated in FASE 26 against measured data - see
  section 7.1 below. See also `AskInsuranceKnowledgeUseCase`'s no-answer policy and
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
also clears `insurance-ai.rag.lexical.min-rank` (FASE 14 audit remediation; see
`docs/adr/ADR-012-AUDIT-REMEDIATION.md`, and section 7.1 below for its calibration). Neither
`fusionScore` nor `rerankerScore` is ever used as grounding evidence - see ADR-006 decision 4 for
why the reranker specifically must never be mistaken for proof of grounding.

### 7.1 Calibrating `lexical.min-rank` (FASE 26)

The value was previously an uncalibrated `0.0` placeholder, explicitly flagged as "must be raised
once real `ts_rank_cd` distributions have been observed". FASE 26 observed them, with the exact
production ranking expression, against the real ingested corpus in the running database
(`calibrate_lexical_min_rank.sql`), over two disjoint question sets:

| Question set | n | Matching chunks | `ts_rank_cd` |
|---|---|---|---|
| In-corpus (answerable) | 8 | 0-2 | **0.0909 - 0.375** |
| Out-of-corpus (unanswerable) | 6 | **0 in every single case** | never computed |

The important finding is a negative one: **this threshold is not what separates grounded from
ungrounded.** `websearch_to_tsquery` has AND semantics, so a question containing any term absent
from the corpus fails `content_tsv @@ query` outright and produces no row to rank at all. Every
out-of-corpus question was rejected by the match operator itself, and would have been at *any*
threshold value. Conversely, genuine matches ranked as low as `0.0909`, so raising the threshold
past that point can only ever start discarding **true** positives.

**Chosen: `0.05`** - deliberately below the measured floor of real matches (a 1.8x margin), so it
changes no measured outcome today, while still rejecting a degenerate `ts_rank_cd = 0.0` match and
providing defence in depth if the matching semantics are ever loosened (a switch to OR-semantics
`to_tsquery` would start admitting weak partial matches, at which point this threshold becomes
load-bearing).

Two honest caveats: these numbers describe *this* corpus and must be re-measured for another, and
the safety of the current configuration rests on AND-semantics matching rather than on the
threshold itself.

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
  **On the Spanish corpus this is not a tuning nicety but a functional defect - see section 12.**
- `QueryExpander`'s synonym map is small and illustrative, not a reviewed insurance thesaurus.
- No Maximal Marginal Relevance / full document-diversity optimization (a simple per-section cap
  is used instead).
- `RetrievalDiagnostics` is logged but not yet persisted for audit/evaluation (FASE 9/10). Only
  three of its five stage counts reach `ai_audit_records` (semantic, lexical, final); the fused
  and reranked counts exist solely in the application log, which is why
  `scripts/audit_retrieval_funnel.py` reads the log rather than the database.

## 12. Measured defect: the lexical branch contributes nothing on the Spanish corpus

Status: **open defect, measured not assumed.** Reproduce with
`scripts/calibrate_lexical_search.py` and `scripts/audit_retrieval_funnel.py`.

Against the running stack (26-document Spanish corpus, 2.037 embedded chunks), across the **248
retrievals** of a full 255-case evaluation run:

| Stage | Mean candidates | Retrievals returning 0 |
|---|---|---|
| semantic | 4,12 | 70 / 248 |
| lexical | **0,05** | **239 / 248 (96,4 %)** |
| fused | 4,15 | 69 / 248 |
| reranked | 4,15 | 69 / 248 |
| final | 4,14 | 69 / 248 |

The lexical branch contributed at least one candidate in **9 of 248 retrievals (3,6 %)**, and was
the *only* contributing branch exactly once. Hybrid search is therefore semantic-only in practice:
`ScoreFusion` almost never has a second list to fuse, and `fused` tracks `semantic` throughout. Any
statement that this system performs hybrid retrieval describes the code, not the measured
behaviour.

### 12.1 Root cause

Two independent causes compound, and they must not be conflated:

1. **Wrong text search configuration.** Spanish content is indexed with `to_tsvector('english',
   ...)`. English stop-word removal does not recognise Spanish function words, and English
   stemming does not fold Spanish accents. The question *"¿Qué cubre la modalidad de terceros
   básico?"* produces the lexemes `'qué' & 'cubr' & 'la' & 'modalidad' & 'de' & 'tercero' &
   'básico'` - `la`, `de` and `qué` survive as *required* terms. The `spanish` configuration
   reduces the same question to `'cubr' & 'modal' & 'tercer' & 'basic'`.
2. **AND semantics.** `websearch_to_tsquery` joins every lexeme with `&`, so a chunk must contain
   *all* of them to match at all. For a natural-language question that is a very strong demand,
   and it is the dominant cause: switching to `spanish` alone does **not** repair the branch.

The index itself is healthy - the single term `franquicia` matches 204 chunks, and `franquicia
lunas` matches 21. The failure is entirely in query construction.

### 12.2 What was measured

30 real `GROUNDED` questions from `docs/testing/evaluation_dataset.csv`, top-8 per strategy:

| Strategy | Questions matching anything | Expected document in top-8 |
|---|---|---|
| `english` + AND (**deployed**) | 0 / 30 | 0 % |
| `spanish` + AND | 1 / 30 | 3 % |
| `english` + OR | 30 / 30 | 60 % |
| `spanish` + OR | 30 / 30 | **73 %** |

### 12.3 Why the obvious fix was rejected

`spanish` + OR looks like the answer, and as a *retrieval* change it is. It was **not** adopted,
because the lexical rank is not only a ranking signal - `AskInsuranceKnowledgeUseCase` grounds an
answer when a candidate clears the semantic threshold **or** clears
`insurance-ai.rag.lexical.min-rank`. That threshold was calibrated under AND semantics, where an
out-of-corpus question matched zero chunks and the threshold never had to discriminate anything.

Under OR semantics every question matches something, so `min-rank` becomes the only barrier
between an unanswerable question and a confidently grounded answer. Measuring both populations
(`scripts/calibrate_lexical_or_threshold.py`, 40 in-corpus vs 40 out-of-corpus questions):

| Population | min | median | max |
|---|---|---|---|
| In corpus (`GROUNDED`) | 0,2000 | 0,6000 | 1,2000 |
| Out of corpus (`NO_ANSWER`) | 0,1000 | 0,2500 | 0,5000 |

The distributions **overlap**: 35 of 40 out-of-corpus questions score above the weakest legitimate
question. **No separating threshold exists.** Adopting OR semantics while leaving the grounding
rule untouched would trade a broken lexical branch for false grounded answers on questions the
corpus cannot answer - a strictly worse failure, and one that would have looked like an
improvement in every retrieval metric while degrading the 58 `NO_ANSWER` cases.

### 12.4 Recommended fix

Correcting this requires a design change, not a configuration change, and both parts are needed:

1. Reindex `content_tsv` with the `spanish` configuration (new Flyway migration; the column is
   `GENERATED ALWAYS ... STORED`, so redefining it recomputes every row - no re-embedding).
2. Adopt OR semantics **and simultaneously** demote the lexical branch to a fusion-only signal:
   let it contribute candidates to RRF, but stop letting `min-rank` alone justify grounding.
   Grounding would then rest on the semantic threshold and the reranker, which is where the
   evidence says the discriminating signal actually lives.

Until both land together, the `english` + AND configuration is retained deliberately: it is inert
rather than actively harmful, and it fails safe.

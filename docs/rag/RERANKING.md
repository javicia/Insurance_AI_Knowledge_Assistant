# Reranking (FASE 6, Advanced RAG)

Status: living document. See `docs/adr/ADR-006-ADVANCED-RAG-RETRIEVAL-STRATEGY.md` decision 4
for why reranking is a port and why the initial implementation is deliberately not ML-based.

## 1. Why a port

`RerankerPort` (`ports.outbound`) exists because reranking is explicitly designed to be
swappable: a future `CrossEncoderReranker` adapter (a real external model call - genuine
infrastructure, real latency/cost) should be able to replace the current implementation without
`HybridRetrievalService` or `AskInsuranceKnowledgeUseCase` changing at all.

```
RerankerPort
   |
   +-- RuleBasedReranker   (FASE 6, PoC heuristic, this document)
   |
   \-- (future) CrossEncoderReranker   (not implemented - a real model call, out of scope here)
```

## 2. `RuleBasedReranker` - what it actually is

**A deterministic, offline, non-ML heuristic. Not a cross-encoder. Not any form of trained
ranking model.** It must never be presented or logged as "AI-powered reranking" - this is
explicit per brief section 11.

Algorithm, exactly:

```
rerankerScore = fusionScore + 0.1 * keywordCoverage(question, candidate.content)

keywordCoverage = |distinct question keywords found in content| / |distinct question keywords|
```

Keywords are extracted with the same simple `[a-z0-9]+` lowercase tokenization used elsewhere in
this codebase (`FakeEmbeddingModelAdapter`). `keywordCoverage` is a value in `[0, 1]`; the `0.1`
weight means reranking can shift ranking by at most a small amount relative to the fusion score -
it nudges order, it does not invert it wholesale. Results are sorted by `rerankerScore`
descending, ties broken by ascending `chunkId` string (deterministic), then truncated to
`insurance-ai.rag.hybrid.final-top-k`.

**What this does prove**: reranking is a real, observable pipeline stage whose effect on
candidate order is independently testable and can differ from plain fusion order
(`RuleBasedRerankerTest.higherKeywordCoverageCanOvertakeAHigherFusionScore`) - exercising the
architecture (a genuine `Hybrid Search -> candidate pool -> Reranker -> final ranking` pipeline)
end to end.

**What this does not prove**: semantic relevance judgment, query-document relationship modeling,
or anything resembling what a real cross-encoder (e.g. a fine-tuned BERT-family model scoring
`(query, passage)` pairs jointly) would provide. `0.1` is not a calibrated weight - it was chosen
so the effect is observable and testable, not derived from any relevance measurement.

## 3. Why the reranker is never grounding evidence

`AskInsuranceKnowledgeUseCase`'s no-answer policy inspects `semanticScore`/`lexicalScore` only -
never `rerankerScore`, and never `fusionScore` either. `HybridRetrievalResult.withRerankerScore`
returns a new record preserving every original field: reranking adds a signal, it never erases
or overwrites the retrieval evidence the grounding decision depends on. This means a real
cross-encoder swap later changes *which* chunks reach the LLM and in what order, but never
changes what counts as "enough evidence to answer at all" - that stays governed purely by the
retrieval branches' own native relevance bars (cosine threshold; PostgreSQL FTS match).

## 4. Configuration

`insurance-ai.rag.reranking.enabled` (default `true`). When `false`,
`HybridRetrievalService` skips `RerankerPort` entirely and truncates the fused candidate pool
directly to `insurance-ai.rag.hybrid.final-top-k` in fusion order - useful for isolating
reranking's effect during evaluation/debugging.

## 5. Tests

`RuleBasedRerankerTest` (unit, no I/O): every returned candidate has a non-null
`rerankerScore`; semantic/lexical/fusion scores are preserved unchanged; a higher-keyword-coverage
candidate can overtake a purely fusion-ranked one; `finalTopK` is respected; an empty candidate
list returns empty; identical input produces identical output (determinism). Reranking's
integration into the full pipeline (enabled vs. disabled) is covered by
`HybridRetrievalServiceTest`.

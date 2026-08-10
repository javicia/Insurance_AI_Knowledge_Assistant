# AI Evaluation (FASE 10)

Status: living document. See `docs/rag/HYBRID_SEARCH.md`/`docs/rag/RERANKING.md` for what is being
evaluated, `docs/audit/AI_AUDIT.md` for the operational (per-request) log this complements.

## 1. Purpose and scope

AI Audit (FASE 9) answers "what happened on this one request." AI Evaluation answers a different
question: "is the pipeline's retrieval/grounding quality what we expect, right now, across a fixed
set of known questions?" It is a small, reproducible regression check for the RAG pipeline, not a
live monitoring system and not a claim of statistically rigorous quality measurement (brief section
10: "a small reproducible dataset").

## 2. The dataset

`InsuranceEvaluationDataset` (`application.evaluation`) - 7 fixed cases, in code rather than an
external file (a PoC-scale dataset does not justify a file format/parser):

| Case | Category | Expected outcome | Expected source |
|---|---|---|---|
| Water damage from a burst pipe | coverage | `GROUNDED` | Home Insurance Policy |
| Flood damage from a natural disaster | exclusions | `GROUNDED` | Policy Exclusions |
| Claims submitted within 30 days of an incident | claims-procedure | `GROUNDED` | Claims Procedure |
| Waiting period before travel medical coverage | waiting-period | `GROUNDED` | Travel Insurance Policy |
| The CEO's salary | out-of-scope | `NO_ANSWER` | - |
| A specific customer's bank account number | out-of-scope | `NO_ANSWER` | - |
| The company's current stock price | out-of-scope | `NO_ANSWER` | - |

Each in-scope question is deliberately worded as a close paraphrase of its paired document's own
sentence (every non-stopword term in the question also appears, verbatim or via English
stemming, in the content) - not an accident of writing style, but a direct consequence of how
retrieval actually finds a match here: `PostgresLexicalSearchAdapter` uses `websearch_to_tsquery`,
which ANDs every unquoted term together, so a single incidental question word absent from a short
one-sentence source document (e.g. an early draft's "How **many** days...") silently zeroes out the
lexical branch; the fake embedding adapter's word-overlap heuristic (`docs/rag/HYBRID_SEARCH.md`)
is similarly sensitive to vocabulary overlap. A production embedding model would tolerate far more
paraphrase distance - this is a known, documented property of this PoC's *fake* semantic adapter
and its short fixture documents, not of the real hybrid retrieval design.

Four in-scope questions (brief FASE 10 section 10's own examples: coverage, exclusions, claims
procedure, waiting period) and three deliberately out-of-scope questions (also brief section 10's
own examples) that must produce the no-answer response. `EvaluationIntegrationTest` ingests four
short documents (one per in-scope case, matching the expected source document names exactly)
before running the dataset - the dataset and that test's fixtures must stay in sync, see that
class's Javadoc.

Prompt injection resistance is **not** duplicated into this dataset: it is already exercised by
FASE 8's `SecurityGuardrailIntegrationTest` against the real pipeline. Evaluating "does the answer
quality regress" and "does the guardrail block an attack" are different questions with different
existing homes; merging them here would blur both.

## 3. Running an evaluation

`EvaluationRunnerService.runBuiltInDataset()` (or `run(datasetName, cases)` for a custom list) runs
every case through the real `AskInsuranceKnowledgeUseCase` - the same use case `POST /api/chat`
calls - and persists one `EvaluationRun`. REST: `POST /api/evaluation/runs` (built-in dataset,
synchronous - brief section 61: no async/queueing machinery for 7 cases), `GET
/api/evaluation/runs/{id}`, `GET /api/evaluation/runs/recent?limit=20`.

## 4. Metrics (`EvaluationMetrics.compute`, pure function, `domain.evaluation`)

| Metric | Meaning |
|---|---|
| `outcomeAccuracy` | Fraction of all cases whose actual outcome matched the expected one |
| `groundingRate` | Among `GROUNDED`-expected cases, fraction actually grounded |
| `noAnswerAccuracy` | Among `NO_ANSWER`-expected cases, fraction actually refused |
| `recallAtK` | Among `GROUNDED`-expected cases, fraction whose expected document appeared anywhere in the returned final candidates |
| `mrr` | Mean reciprocal rank of the expected document's position among returned sources, over `GROUNDED`-expected cases |
| `citationCoverage` | Among cases that were actually grounded, fraction with at least one citation |

**Precision@K is explicitly not computed** - the dataset labels exactly one relevant document per
question with no relevance judgement recorded for the rest of what retrieval might surface, so a
precision figure would be either uninformative or fabricated. Per brief section 10's own
instruction, this is stated here rather than invented.

Every ratio is **vacuously `1.0`** (or `0.0` for `mrr`) when its underlying subset is empty - e.g.
`recallAtK` reads `1.0` for a dataset with zero `GROUNDED`-expected cases. This mirrors "zero
assertions is not the same as proof" - always read a ratio next to `totalCases`, never alone.

## 5. Regression detection (thresholds)

`insurance-ai.evaluation.thresholds.*` (`InsuranceAiProperties.Evaluation.Thresholds`,
`application.yaml`): `min-grounding-rate` (default `1.0`), `min-no-answer-accuracy` (default
`1.0`), `min-recall-at-k` (default `0.75`) - PoC-level starting values for the 7-case built-in
dataset, not scientifically calibrated (see that config block's own comment). `EvaluationRunStatus`
is `PASSED` only if every threshold is met; otherwise `FAILED`. This is the PoC's regression gate:
a run whose metrics have regressed below the configured floor fails immediately, without needing a
second run to diff against - simpler than baseline-comparison for a dataset this size, and
documented as such rather than hidden as if it were more sophisticated.

## 6. Persistence

`evaluation_runs` (aggregate-level metrics/status) + `evaluation_case_results` (one child row per
case, FK'd to the run, `position` preserving original dataset order) - `V6__ai_evaluation.sql`.
Both are append-only, excluded from `DatabaseCleanupExtension`'s TRUNCATE list for the same reason
as `ai_audit_records` (FASE 9): no cross-test collision risk, since every run gets a freshly
generated `EvaluationRunId`.

## 7. Tests

Unit: `EvaluationMetricsTest` (pure arithmetic over a hand-built `EvaluationCaseResult` list,
including the empty-list vacuous-value case), `EvaluationRunnerServiceTest` (mocked
`AskInsuranceKnowledgeUseCase`/`EvaluationRunRepository` - both a `PASSED` and a `FAILED` run).
Integration (real Postgres): `EvaluationIntegrationTest` - ingests the four matching documents,
runs the built-in dataset through the real pipeline, asserts a `PASSED` run with perfect
`groundingRate`/`noAnswerAccuracy`/`recallAtK`, and that the run is genuinely re-readable via
`findById` after being persisted.

## 8. Current limitations

- 7 cases is enough to prove the mechanism works end-to-end, not enough for statistically
  meaningful metrics - a production evaluation harness would need a substantially larger, ideally
  domain-expert-curated dataset.
- No Precision@K (see section 4) - would require additional relevance judgements this PoC's
  dataset does not carry.
- No per-provider comparison run (brief section 10 mentions "provider consistency" as a capability
  to evaluate) - `EvaluationRunnerService.run` is provider-agnostic (it just calls the currently
  wired `AskInsuranceKnowledgeUseCase`), so comparing providers today means re-running with a
  different `insurance-ai.ai.provider` and comparing the two persisted runs by hand; no dedicated
  comparison feature exists.
- No UI - REST API and direct repository queries only.
- No authentication on the evaluation REST API (same PoC-wide limitation as governance/audit).
- Thresholds are single fixed values, not per-category - a regression isolated to one category
  (e.g. `exclusions`) is visible in the persisted per-case results but does not independently fail
  the gate unless it drags an aggregate ratio below threshold.

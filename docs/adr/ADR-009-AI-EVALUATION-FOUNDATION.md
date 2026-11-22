# ADR-009: AI Evaluation Foundation (Dataset, Runner, Metrics, Regression Gate)

## Status

Accepted — FASE 10.

## Context

FASE 10 (AI Evaluation) needs a real, reproducible way to answer "has retrieval/grounding quality
regressed" - distinct from FASE 9's AI Audit, which answers "what happened on one request", not
"is the pipeline still behaving as expected across a fixed set of known questions."

## Decisions

**1. The dataset is fixed Java data, not an external file.** `InsuranceEvaluationDataset` holds 7
`EvaluationCase` values in code, the same style as `WellKnownAiSystems`/`InsuranceRagSystemPrompt`'s
historical seed content. A dataset this small does not justify a file format, parser, or loader -
that indirection would only pay off at a scale this PoC does not have (brief section 61).

**2. `EvaluationRunnerService.run` calls the real `AskInsuranceKnowledgeUseCase`, never a shortcut
around it.** The same use case `POST /api/chat` calls. An evaluation that measured a parallel/mocked
pipeline would risk silently drifting from what production traffic actually experiences - the
entire point of a regression check is that it observes the real thing.

**3. Precision@K is deliberately not computed.** The dataset labels exactly one relevant document
per in-scope question with no relevance judgement recorded for whatever else retrieval might
surface - a precision figure here would either be uninformative (trivially `1/finalTopK` shaped) or
require inventing ground truth that was never curated. `EvaluationMetrics`'s Javadoc states this
plainly, per brief section 10's own instruction to say "not evaluated due to insufficient ground
truth" rather than fabricate a number.

**4. Every ratio metric is vacuously `1.0` (`0.0` for `mrr`) over an empty subset.** Consistent with
how an empty JUnit assertion set is reported as passing: nothing was contradicted, which is not the
same as something being proven. Documented explicitly on `EvaluationMetrics` so a reader does not
mistake a vacuous `1.0` for "verified perfect."

**5. Regression detection is threshold-based, not baseline-diffing.** `EvaluationRunnerService`
compares a completed run's metrics against `insurance-ai.evaluation.thresholds.*`
(`InsuranceAiProperties.Evaluation.Thresholds`) and marks the run `PASSED`/`FAILED` immediately -
no second run is needed to detect a regression. Rejected alternative: storing a designated
"baseline" run and diffing every new run against it - meaningfully more machinery (baseline
selection/promotion) than a 7-case PoC dataset warrants; a fixed floor is simpler and, for this
dataset's stability, equally effective at catching an actual regression.

**6. `EvaluationRun` is an immutable record, not a mutable aggregate with behaviour.** Same
reasoning as `AuditRecord` (`ADR-008` decision 6): a pure fact once every case has run and metrics
have been computed - there is no later mutation to model.

**7. Prompt injection resistance is not duplicated into this dataset.** It is already exercised by
FASE 8's `SecurityGuardrailIntegrationTest` against the real pipeline. Evaluating answer-quality
regression and evaluating guardrail effectiveness are different questions; conflating them into one
dataset would blur both rather than strengthen either.

**8. In-scope evaluation questions are worded as close paraphrases of their paired document's own
sentence.** Discovered empirically while building `EvaluationIntegrationTest`: `PostgresLexicalSearchAdapter`'s
`websearch_to_tsquery` ANDs every unquoted question term, so one incidental word absent from a short
one-sentence fixture document (an early draft's "How **many** days...", with "many" nowhere in the
source text) silently zeroed the lexical branch; the *fake* embedding adapter's word-overlap
heuristic is similarly sensitive. This is a property of this PoC's fake semantic adapter and
short fixture documents, not of the real hybrid retrieval design - documented in
`docs/evaluation/AI_EVALUATION.md` rather than worked around with a higher-fidelity fake adapter,
which brief section 61 would call overengineering for a PoC evaluation fixture.

## Consequences

- `InsuranceAiProperties` gained an `Evaluation.Thresholds` section (`min-grounding-rate`,
  `min-no-answer-accuracy`, `min-recall-at-k`) - PoC-level starting values, explicitly documented as
  not scientifically calibrated.
- `EvaluationMetrics.compute` is a pure domain function (`domain.evaluation`), independent of
  configuration/persistence - `EvaluationRunnerService` (application layer) is the only place that
  knows about thresholds, matching how `PromptRegistryService` (not `Prompt`) owns the
  one-active-per-key invariant (`ADR-008` decision 3).
- `evaluation_runs`/`evaluation_case_results` (`V6__ai_evaluation.sql`) are excluded from
  `DatabaseCleanupExtension`'s TRUNCATE list, for the same append-only/no-collision-risk reasoning
  as `ai_audit_records`.
- `EvaluationIntegrationTest` is the proof this is not evaluation theater: it ingests real
  documents, runs the built-in dataset through the real pipeline, and asserts a genuinely `PASSED`
  run with perfect `groundingRate`/`noAnswerAccuracy`/`recallAtK` - not a mocked approximation.

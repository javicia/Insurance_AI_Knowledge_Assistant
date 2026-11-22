-- AI Evaluation schema (FASE 10): one row per EvaluationRun, one child row per EvaluationCaseResult.
-- Flyway remains the sole schema owner. See docs/evaluation/AI_EVALUATION.md.

CREATE TABLE evaluation_runs (
    id                  UUID PRIMARY KEY,
    dataset_name        TEXT NOT NULL,
    started_at          TIMESTAMPTZ NOT NULL,
    completed_at        TIMESTAMPTZ NOT NULL,
    total_cases         INTEGER NOT NULL,
    outcome_accuracy    DOUBLE PRECISION NOT NULL,
    grounding_rate      DOUBLE PRECISION NOT NULL,
    no_answer_accuracy  DOUBLE PRECISION NOT NULL,
    recall_at_k         DOUBLE PRECISION NOT NULL,
    mrr                 DOUBLE PRECISION NOT NULL,
    citation_coverage   DOUBLE PRECISION NOT NULL,
    status              VARCHAR(16) NOT NULL
);

CREATE INDEX ix_evaluation_runs_started_at ON evaluation_runs (started_at DESC);

-- Deliberately not FK'd for CASCADE deletion beyond the run relationship itself - a run and its
-- case results are always written together, in one JdbcEvaluationRunRepository#save call, never
-- independently.
CREATE TABLE evaluation_case_results (
    id               UUID PRIMARY KEY,
    run_id           UUID NOT NULL REFERENCES evaluation_runs (id),
    position         INTEGER NOT NULL,
    case_id          TEXT NOT NULL,
    category         TEXT NOT NULL,
    expected_outcome VARCHAR(16) NOT NULL,
    actual_outcome   VARCHAR(16) NOT NULL,
    outcome_match    BOOLEAN NOT NULL,
    source_hit       BOOLEAN,
    reciprocal_rank  DOUBLE PRECISION,
    citation_count   INTEGER NOT NULL,
    trace_id         TEXT NOT NULL
);

CREATE INDEX ix_evaluation_case_results_run_id ON evaluation_case_results (run_id, position);

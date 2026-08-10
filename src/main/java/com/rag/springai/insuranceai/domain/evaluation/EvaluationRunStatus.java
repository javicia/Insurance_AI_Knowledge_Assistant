package com.rag.springai.insuranceai.domain.evaluation;

/**
 * Whether a completed {@link EvaluationRun}'s metrics met the configured quality gate (brief
 * FASE 10 section 10's "thresholds, regression detection") - decided by {@code
 * EvaluationRunnerService} against {@code insurance-ai.evaluation.thresholds.*}, not by this
 * enum itself; a run's status is a recorded fact once the aggregate exists.
 */
public enum EvaluationRunStatus {
    PASSED,
    FAILED
}

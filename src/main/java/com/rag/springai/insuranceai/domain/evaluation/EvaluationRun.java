package com.rag.springai.insuranceai.domain.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One immutable record of running a dataset through {@code AskInsuranceKnowledgeUseCase} (brief
 * FASE 10 section 10) - a pure fact once created, same shape of design as {@code AuditRecord}
 * (FASE 9): no behaviour beyond construction, built by {@code EvaluationRunnerService} in one
 * shot once every case has been run and {@link EvaluationMetrics} computed.
 */
public record EvaluationRun(EvaluationRunId id, String datasetName, Instant startedAt, Instant completedAt,
        List<EvaluationCaseResult> results, EvaluationMetrics metrics, EvaluationRunStatus status) {

    public EvaluationRun {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(datasetName, "datasetName must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        Objects.requireNonNull(results, "results must not be null");
        Objects.requireNonNull(metrics, "metrics must not be null");
        Objects.requireNonNull(status, "status must not be null");
        results = List.copyOf(results);
    }
}

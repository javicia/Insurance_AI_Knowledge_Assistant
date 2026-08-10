package com.rag.springai.insuranceai.adapters.inbound.rest.evaluation;

import com.rag.springai.insuranceai.domain.evaluation.EvaluationRun;

import java.util.List;

record EvaluationRunResponse(String id, String datasetName, String startedAt, String completedAt, String status,
        EvaluationMetricsResponse metrics, List<EvaluationCaseResultResponse> results) {

    static EvaluationRunResponse from(EvaluationRun run) {
        return new EvaluationRunResponse(run.id().toString(), run.datasetName(), run.startedAt().toString(),
                run.completedAt().toString(), run.status().name(), EvaluationMetricsResponse.from(run.metrics()),
                run.results().stream().map(EvaluationCaseResultResponse::from).toList());
    }
}

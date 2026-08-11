package com.rag.springai.insuranceai.adapters.inbound.rest.evaluation;

import com.rag.springai.insuranceai.domain.evaluation.EvaluationMetrics;

record EvaluationMetricsResponse(int totalCases, double outcomeAccuracy, double groundingRate,
        double noAnswerAccuracy, double recallAtK, double mrr, double citationCoverage) {

    static EvaluationMetricsResponse from(EvaluationMetrics metrics) {
        return new EvaluationMetricsResponse(metrics.totalCases(), metrics.outcomeAccuracy(), metrics.groundingRate(),
                metrics.noAnswerAccuracy(), metrics.recallAtK(), metrics.mrr(), metrics.citationCoverage());
    }
}

package com.rag.springai.insuranceai.adapters.inbound.rest.evaluation;

import com.rag.springai.insuranceai.domain.evaluation.EvaluationCaseResult;

record EvaluationCaseResultResponse(String caseId, String category, String expectedOutcome, String actualOutcome,
        boolean outcomeMatch, Boolean sourceHit, Double reciprocalRank, int citationCount, String traceId) {

    static EvaluationCaseResultResponse from(EvaluationCaseResult result) {
        return new EvaluationCaseResultResponse(result.caseId(), result.category(), result.expectedOutcome().name(),
                result.actualOutcome().name(), result.outcomeMatch(), result.sourceHit(), result.reciprocalRank(),
                result.citationCount(), result.traceId());
    }
}

package com.rag.springai.insuranceai.domain.evaluation;

import java.util.Objects;

/**
 * What actually happened when one {@link EvaluationCase} was run through
 * {@code AskInsuranceKnowledgeUseCase} (brief FASE 10 section 10).
 *
 * <p>{@code sourceHit} and {@code reciprocalRank} are {@code null} whenever {@code
 * expectedOutcome} is {@link ExpectedOutcome#NO_ANSWER} - there is no expected document to
 * check position against for a question that should not be answered at all. {@code
 * citationCount} is always populated (0 for a no-answer/blocked actual outcome).
 */
public record EvaluationCaseResult(String caseId, String category, ExpectedOutcome expectedOutcome,
        ExpectedOutcome actualOutcome, boolean outcomeMatch, Boolean sourceHit, Double reciprocalRank,
        int citationCount, String traceId) {

    public EvaluationCaseResult {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(expectedOutcome, "expectedOutcome must not be null");
        Objects.requireNonNull(actualOutcome, "actualOutcome must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        if (citationCount < 0) {
            throw new IllegalArgumentException("citationCount must not be negative");
        }
    }
}

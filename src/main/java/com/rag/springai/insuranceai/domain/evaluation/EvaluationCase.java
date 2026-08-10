package com.rag.springai.insuranceai.domain.evaluation;

import java.util.Objects;

/**
 * One question in a reproducible evaluation dataset (brief FASE 10 section 10): a question,
 * what it is expected to do ({@link ExpectedOutcome}), and - only when {@link
 * ExpectedOutcome#GROUNDED} - which document name the grounded answer is expected to cite.
 *
 * <p>{@code expectedSourceDocument} is deliberately a single document name, not a set: this
 * PoC's dataset labels exactly one relevant document per in-scope question (brief FASE 10
 * section 10's "small reproducible dataset"), which is enough to compute {@code recallAtK}/
 * {@code mrr} honestly but not enough to compute Precision@K (see {@link EvaluationMetrics}'s
 * Javadoc) - a deliberate scope limit, not an oversight.
 */
public record EvaluationCase(String caseId, String question, String category, ExpectedOutcome expectedOutcome,
        String expectedSourceDocument) {

    public EvaluationCase {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(question, "question must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(expectedOutcome, "expectedOutcome must not be null");
        if (expectedOutcome == ExpectedOutcome.GROUNDED) {
            Objects.requireNonNull(expectedSourceDocument,
                    "expectedSourceDocument must not be null for a GROUNDED case");
        }
    }
}

package com.rag.springai.insuranceai.domain.evaluation;

/**
 * The two outcomes an {@link EvaluationCase} can expect from {@code AskInsuranceKnowledgeUseCase}
 * (brief FASE 10 section 10). Matches {@code GroundingStatus} one-for-one, not reused directly:
 * this type belongs to the evaluation dataset's vocabulary (what a human author expects), not the
 * use case's runtime vocabulary - the two are deliberately kept independent so a change to one
 * does not silently change the meaning of the other.
 */
public enum ExpectedOutcome {
    GROUNDED,
    NO_ANSWER
}

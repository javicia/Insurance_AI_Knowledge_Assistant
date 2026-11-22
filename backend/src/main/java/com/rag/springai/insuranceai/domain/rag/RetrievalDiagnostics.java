package com.rag.springai.insuranceai.domain.rag;

import java.util.Objects;

/**
 * Internal record of how many candidates survived each Advanced RAG pipeline stage (brief FASE 6
 * section 25) - semantic/lexical retrieval, RRF fusion, reranking, final context selection.
 * Logged at each request (brief section 42), not exposed over {@code POST /api/chat} by default;
 * kept as an explicit type precisely so a future AI Audit/Evaluation persistence step (FASE 9/10)
 * has something ready to record rather than needing to reconstruct this after the fact.
 */
public record RetrievalDiagnostics(RetrievalOutcome outcome, int semanticCandidateCount, int lexicalCandidateCount,
        int fusedCandidateCount, int rerankedCandidateCount, int finalCandidateCount) {

    public RetrievalDiagnostics {
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}

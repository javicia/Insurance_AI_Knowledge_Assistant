package com.rag.springai.insuranceai.domain.rag;

/**
 * Which retrieval branches actually executed successfully for one {@code
 * AskInsuranceKnowledgeUseCase} request (brief FASE 6 section 29/30 - partial failure/graceful
 * degradation). Distinct from {@link RetrievalSource}, which is per-candidate provenance within
 * a successful retrieval; this is the per-request execution outcome. {@code FAILED} means both
 * branches failed - {@code HybridRetrievalService} throws rather than silently returning an
 * empty/no-answer result in that case, so {@code FAILED} is not expected to reach {@link
 * RetrievalDiagnostics} in practice, but is kept for completeness/future AI Audit use.
 */
public enum RetrievalOutcome {
    HYBRID,
    SEMANTIC_ONLY,
    LEXICAL_ONLY,
    FAILED
}

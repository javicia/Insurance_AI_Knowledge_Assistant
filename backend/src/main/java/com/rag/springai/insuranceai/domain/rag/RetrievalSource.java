package com.rag.springai.insuranceai.domain.rag;

/**
 * Which retrieval branch(es) actually surfaced a given {@link HybridRetrievalResult} candidate
 * (brief FASE 6 section 24: "por qué este chunk llegó al contexto"). Derived from whether {@code
 * semanticRank}/{@code lexicalRank} are present, never stored independently - see {@link
 * HybridRetrievalResult#source()}.
 */
public enum RetrievalSource {
    HYBRID,
    SEMANTIC_ONLY,
    LEXICAL_ONLY
}

package com.rag.springai.insuranceai.ports.outbound;

import com.rag.springai.insuranceai.domain.rag.HybridRetrievalResult;

import java.util.List;

/**
 * Outbound port for reordering/re-scoring a fused candidate pool before final context selection
 * (brief FASE 6 section 10/12). A port rather than a plain application-layer class because it is
 * explicitly designed to be swappable: the initial implementation ({@code RuleBasedReranker}) is
 * a deterministic PoC heuristic, never presented as ML-quality (section 11), but the interface
 * is shaped so a future {@code CrossEncoderReranker} adapter (a real external model call) can
 * replace it without {@code AskInsuranceKnowledgeUseCase}/{@code HybridRetrievalService} changing
 * (section 12).
 */
public interface RerankerPort {

    /**
     * Returns at most {@code finalTopK} candidates from {@code candidates}, re-scored and
     * reordered relative to {@code question}. Every returned candidate must carry a non-null
     * {@code rerankerScore} ({@link HybridRetrievalResult#withRerankerScore}); other fields
     * (semantic/lexical scores and ranks, fusion score) must be preserved unchanged - the
     * reranker adds a signal, it does not erase the retrieval evidence used by the no-answer
     * policy (brief section 21/23: "the reranker is not proof of grounding").
     */
    List<HybridRetrievalResult> rerank(String question, List<HybridRetrievalResult> candidates, int finalTopK);
}

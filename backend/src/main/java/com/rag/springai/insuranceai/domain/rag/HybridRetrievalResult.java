package com.rag.springai.insuranceai.domain.rag;

import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentChunkId;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;

import java.util.Objects;

/**
 * A single retrieval candidate as it flows through the Advanced RAG pipeline (brief FASE 6
 * section 9): produced by {@code ScoreFusion} (semantic + lexical scores/ranks always set at
 * that point, {@code fusionScore} always present), optionally re-scored by {@code RerankerPort}
 * ({@code rerankerScore} stays {@code null} until then). Nothing is overwritten in place - each
 * stage returns new instances so the original semantic/lexical evidence is never lost, which is
 * what makes citations able to answer "why did this chunk reach the context" (section 24).
 *
 * <p>{@code semanticScore}/{@code semanticRank} and {@code lexicalScore}/{@code lexicalRank}/
 * {@code lexicalTermCoverage} are independently nullable: a candidate found by only one
 * retrieval branch has the other branch's fields set to {@code null} (never a fabricated 0),
 * which is exactly what {@link #source()} reports and what {@code
 * AskInsuranceKnowledgeUseCase}'s no-answer policy inspects directly - see
 * {@code docs/rag/HYBRID_SEARCH.md}.
 *
 * <p><b>{@code lexicalTermCoverage} (FASE 28):</b> carried alongside {@code lexicalScore}
 * because it, not the score, is what the grounding decision may legitimately rest on - see
 * {@link LexicalSearchResult#termCoverage()} for the measurement that motivated it. It travels
 * with the candidate rather than being recomputed downstream so that grounding inspects the
 * coverage of the <em>actual query that retrieved this chunk</em>, which is the only query it
 * is meaningful against.
 */
public record HybridRetrievalResult(DocumentChunkId chunkId, DocumentId documentId,
        DocumentVersionId documentVersionId, String content, ChunkMetadata metadata, Double semanticScore,
        Integer semanticRank, Double lexicalScore, Integer lexicalRank, Double lexicalTermCoverage,
        double fusionScore, Double rerankerScore) {

    public HybridRetrievalResult {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(documentVersionId, "documentVersionId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
    }

    /** Returns a copy with {@code rerankerScore} set - the only field {@code RerankerPort} may add. */
    public HybridRetrievalResult withRerankerScore(double newRerankerScore) {
        return new HybridRetrievalResult(chunkId, documentId, documentVersionId, content, metadata, semanticScore,
                semanticRank, lexicalScore, lexicalRank, lexicalTermCoverage, fusionScore, newRerankerScore);
    }

    public RetrievalSource source() {
        boolean hasSemantic = semanticRank != null;
        boolean hasLexical = lexicalRank != null;
        if (hasSemantic && hasLexical) {
            return RetrievalSource.HYBRID;
        }
        return hasSemantic ? RetrievalSource.SEMANTIC_ONLY : RetrievalSource.LEXICAL_ONLY;
    }
}

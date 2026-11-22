package com.rag.springai.insuranceai.domain.rag;

import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentChunkId;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;

import java.util.Objects;

/**
 * A chunk returned by {@code LexicalSearchPort}, ranked by PostgreSQL full-text search relevance
 * (brief FASE 6 section 3). Deliberately a separate type from {@link RetrievedChunk}, not a
 * relabeling of it: {@code lexicalScore} is a {@code ts_rank_cd} value (unbounded, PostgreSQL's
 * own ranking scale), never comparable to {@link RetrievedChunk#similarityScore()}'s cosine
 * similarity ([-1, 1]) without going through {@code ScoreFusion} first - see
 * {@code docs/rag/HYBRID_SEARCH.md} section on why the two scores are never mixed directly.
 *
 * <p><b>{@code queryTermCount}/{@code matchedTermCount} (FASE 28):</b> how many distinct lexemes
 * the query produced, and how many of them this chunk actually contains. They exist because
 * {@code lexicalScore} cannot answer the only question that matters for grounding: {@code
 * ts_rank_cd} is unnormalized and incomparable across queries, so a high rank proves a chunk
 * ranked well <em>among the rows that matched this query</em>, never that the chunk substantively
 * covers the question. Measured: an out-of-corpus question ("does my HOME insurance cover
 * flooding") scored {@code ts_rank_cd} 2.0 - 40x the configured {@code min-rank} - against an
 * auto-policy chunk, purely because both mention "cubrir" and "seguro". Its {@link
 * #termCoverage()} was 0.40, against 0.67-1.00 for in-corpus questions. Coverage separates them;
 * rank does not.
 */
public record LexicalSearchResult(DocumentChunkId chunkId, DocumentId documentId, DocumentVersionId documentVersionId,
        String content, ChunkMetadata metadata, double lexicalScore, int queryTermCount, int matchedTermCount) {

    public LexicalSearchResult {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(documentVersionId, "documentVersionId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (queryTermCount < 0 || matchedTermCount < 0 || matchedTermCount > queryTermCount) {
            throw new IllegalArgumentException("matchedTermCount must be within [0, queryTermCount], got "
                    + matchedTermCount + " of " + queryTermCount);
        }
    }

    /**
     * The fraction of the question's distinct lexemes this chunk contains, in {@code [0, 1]} -
     * the query-length-normalized evidence signal {@code lexicalScore} is not. {@code 0.0} when
     * the query carried no searchable lexemes at all (an all-stopword question), which is
     * correctly "no lexical evidence" rather than a division by zero.
     */
    public double termCoverage() {
        return queryTermCount == 0 ? 0.0 : (double) matchedTermCount / queryTermCount;
    }
}

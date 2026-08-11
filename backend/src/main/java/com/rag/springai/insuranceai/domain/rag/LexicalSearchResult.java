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
 */
public record LexicalSearchResult(DocumentChunkId chunkId, DocumentId documentId, DocumentVersionId documentVersionId,
        String content, ChunkMetadata metadata, double lexicalScore) {

    public LexicalSearchResult {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(documentVersionId, "documentVersionId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
    }
}

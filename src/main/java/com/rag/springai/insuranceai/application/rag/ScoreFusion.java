package com.rag.springai.insuranceai.application.rag;

import com.rag.springai.insuranceai.domain.document.ChunkMetadata;
import com.rag.springai.insuranceai.domain.document.DocumentChunkId;
import com.rag.springai.insuranceai.domain.document.DocumentId;
import com.rag.springai.insuranceai.domain.document.DocumentVersionId;
import com.rag.springai.insuranceai.domain.rag.HybridRetrievalResult;
import com.rag.springai.insuranceai.domain.rag.LexicalSearchResult;
import com.rag.springai.insuranceai.domain.rag.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines a semantic result list and a lexical result list into one ranked candidate list using
 * Reciprocal Rank Fusion (brief FASE 6 section 8): {@code RRF(d) = 1/(k+rankSemantic(d)) +
 * 1/(k+rankLexical(d))}, where a missing rank (a chunk found by only one branch) simply
 * contributes 0 rather than a fabricated rank/score. RRF was chosen specifically because cosine
 * similarity ([-1, 1]) and {@code ts_rank_cd} (PostgreSQL's own unbounded scale) are not on
 * comparable scales - fusing by rank rather than raw score avoids needing to normalize two
 * incompatible distributions. See {@code docs/rag/HYBRID_SEARCH.md} for the full rationale,
 * including why {@code k} is configurable ({@code insurance-ai.rag.hybrid.rrf-k}) and how ties
 * are resolved.
 *
 * <p>Pure computation, no I/O - deliberately not a port (brief section 1: only genuinely
 * swappable/external dependencies get one).
 */
@Component
public class ScoreFusion {

    public List<HybridRetrievalResult> fuse(List<RetrievedChunk> semanticResults,
            List<LexicalSearchResult> lexicalResults, double rrfK) {
        Map<DocumentChunkId, Candidate> candidatesByChunkId = new LinkedHashMap<>();

        for (int i = 0; i < semanticResults.size(); i++) {
            RetrievedChunk chunk = semanticResults.get(i);
            int rank = i + 1;
            Candidate candidate = candidatesByChunkId.computeIfAbsent(chunk.chunkId(),
                    id -> new Candidate(chunk.chunkId(), chunk.documentId(), chunk.documentVersionId(),
                            chunk.content(), chunk.metadata()));
            candidate.semanticScore = chunk.similarityScore();
            candidate.semanticRank = rank;
        }

        for (int i = 0; i < lexicalResults.size(); i++) {
            LexicalSearchResult result = lexicalResults.get(i);
            int rank = i + 1;
            Candidate candidate = candidatesByChunkId.computeIfAbsent(result.chunkId(),
                    id -> new Candidate(result.chunkId(), result.documentId(), result.documentVersionId(),
                            result.content(), result.metadata()));
            candidate.lexicalScore = result.lexicalScore();
            candidate.lexicalRank = rank;
        }

        List<HybridRetrievalResult> fused = new ArrayList<>();
        for (Candidate candidate : candidatesByChunkId.values()) {
            double fusionScore = rrfContribution(candidate.semanticRank, rrfK) + rrfContribution(candidate.lexicalRank,
                    rrfK);
            fused.add(new HybridRetrievalResult(candidate.chunkId, candidate.documentId, candidate.documentVersionId,
                    candidate.content, candidate.metadata, candidate.semanticScore, candidate.semanticRank,
                    candidate.lexicalScore, candidate.lexicalRank, fusionScore, null));
        }

        // Deterministic tie-break: equal fusionScore falls back to chunkId ordering rather than
        // leaving ties in whatever order the LinkedHashMap happened to produce (brief section 8).
        return fused.stream()
                .sorted(Comparator.comparingDouble(HybridRetrievalResult::fusionScore)
                        .reversed()
                        .thenComparing(result -> result.chunkId().toString()))
                .toList();
    }

    private static double rrfContribution(Integer rank, double rrfK) {
        return rank == null ? 0.0 : 1.0 / (rrfK + rank);
    }

    private static final class Candidate {
        private final DocumentChunkId chunkId;
        private final DocumentId documentId;
        private final DocumentVersionId documentVersionId;
        private final String content;
        private final ChunkMetadata metadata;
        private Double semanticScore;
        private Integer semanticRank;
        private Double lexicalScore;
        private Integer lexicalRank;

        private Candidate(DocumentChunkId chunkId, DocumentId documentId, DocumentVersionId documentVersionId,
                String content, ChunkMetadata metadata) {
            this.chunkId = chunkId;
            this.documentId = documentId;
            this.documentVersionId = documentVersionId;
            this.content = content;
            this.metadata = metadata;
        }
    }
}
